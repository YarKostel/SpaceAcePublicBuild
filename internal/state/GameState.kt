package com.spaceace.game.state

import com.spaceace.game.Config
import com.spaceace.game.hazard.HazardBoard
import com.spaceace.game.input.Control
import com.spaceace.game.input.KeyBindings
import com.spaceace.game.model.Entity
import com.spaceace.game.model.EntityId
import com.spaceace.game.model.Npc
import com.spaceace.game.model.PermanentItem
import com.spaceace.game.model.Player
import com.spaceace.game.model.PowerUpType
import com.spaceace.game.model.ShipType
import com.spaceace.game.model.WeaponType
import com.spaceace.game.model.newPlayer
import com.spaceace.game.weapon.Trigger
import kotlin.random.Random

// --- Состояние игры ---

/**
 * MENU → SAVES (выбор слота сохранения) → HANGAR (выбор корабля, магазин) → LOADOUT (подвес, старт) →
 * PLAYING ⇄ PAUSE, SHOP между уровнями, GAME_OVER / VICTORY в конце → снова HANGAR.
 * SETTINGS открываются из MENU, HANGAR и PAUSE и возвращают туда же.
 */
enum class ScreenState { MENU, SAVES, HANGAR, LOADOUT, META_SHOP, BESTIARY, SETTINGS, PLAYING, PAUSE, SHOP, GAME_OVER, VICTORY }

/** Бесконечное хранилище постоянных предметов забега. */
data class ItemStorage(val items: List<PermanentItem> = emptyList()) {
    fun add(item: PermanentItem) = copy(items = items + item)
}

/** Сбрасывается при каждом новом забеге. */
data class RunState(
    val phase: Phase = Phase.START,
    /** Сколько секунд игрового времени прошло в текущей фазе. */
    val phaseTime: Float = 0f,
    /** Текущее оружие — одно из оружий в слотах. */
    val weapon: WeaponType = ShipType.Rebel.stats.baseWeapon,
    val trigger: Trigger = Trigger(),
    val storage: ItemStorage = ItemStorage(),
    val inventory: PowerUpInventory = PowerUpInventory.empty(),
    val effects: ActiveEffects = ActiveEffects(),
    /** Витрина текущего визита в магазин; null вне фазы Shop. */
    val shop: Shop? = null, //заменить на актуальную ноду
    /** Призывы, заказанные слыотами и ждущие игровой логики (источник — расходник или перк). */
    val summonRequests: List<PowerUpType> = emptyList(),
)

/**
 * Корень состояния игры. Переходы экранов и действия игрока возвращают false, если недопустимы
 * в текущем состоянии — повторное нажатие кнопки UI не должно ронять игру.
 */
class GameState(
    meta: MetaState = MetaState(),
    private val random: Random = Random.Default,
    private val saves: SaveLibrary = MemorySaves(),
    private val settingsLibrary: SettingsLibrary = MemorySettings(),
) {
    var settings: Settings = settingsLibrary.read()
        private set
    /** Экран, на который вернут настройки; null — настройки закрыты. */
    private var settingsReturn: ScreenState? = null

    var meta: MetaState = meta
        private set
    var run: RunState = RunState()
        private set
    var screen: ScreenState = ScreenState.MENU
        private set

    /** Слоты, как они были при входе на экран выбора; null — пустой слот. */
    var slots: List<MetaState?> = emptyList()
        private set
    /** Слот, в который сохраняется [meta]; null — ещё не выбран. */
    var activeSlot: Int? = null
        private set

    val entities = EntityRegistry()

    /** Лучи и опасные зоны на поле. */
    val hazards = HazardBoard()

    val player: Player? get() = entities.all.firstOrNull { it is Player } as Player?

    // --- Сохранения ---

    fun openSaves(): Boolean {
        if (screen != ScreenState.MENU && screen != ScreenState.HANGAR) return false
        slots = saves.read()
        screen = ScreenState.SAVES
        return true
    }

    /** Занятый слот загружается, пустой начинает новую игру (файл создаётся сразу); дальше — ангар. */
    fun chooseSave(slot: Int): Boolean {
        if (screen != ScreenState.SAVES || slot !in slots.indices) return false
        val saved = slots[slot]
        meta = saved ?: MetaState().also { saves.write(slot, it) }
        activeSlot = slot
        screen = ScreenState.HANGAR
        return true
    }

    fun deleteSave(slot: Int): Boolean {
        if (screen != ScreenState.SAVES || slots.getOrNull(slot) == null) return false
        saves.delete(slot)
        // Иначе автосохранение воскресило бы удалённый слот.
        if (activeSlot == slot) activeSlot = null
        slots = saves.read()
        return true
    }

    /**
     * Видно ли игровое поле под экраном: в забеге и на экранах поверх него. В меню поле скрыто,
     * иначе под ангаром и бестиарием остались бы сущности прошлого забега.
     */
    val showsField: Boolean
        get() = screen in FIELD_SCREENS || (screen == ScreenState.SETTINGS && settingsReturn == ScreenState.PAUSE)

    // --- Настройки ---

    fun openSettings(): Boolean {
        if (screen !in SETTINGS_FROM) return false
        settingsReturn = screen
        screen = ScreenState.SETTINGS
        return true
    }

    fun closeSettings(): Boolean {
        if (screen != ScreenState.SETTINGS) return false
        screen = settingsReturn ?: ScreenState.MENU
        settingsReturn = null
        return true
    }

    /** Клавиша занята другим действием — снимается с него. Esc не назначается. Раскладка сохраняется сразу. */
    fun rebind(control: Control, key: Int): Boolean {
        if (screen != ScreenState.SETTINGS || key == Config.Controls.RESERVED_KEY) return false
        updateSettings(settings.copy(bindings = settings.bindings.bind(control, key)))
        return true
    }

    fun resetControls(): Boolean {
        if (screen != ScreenState.SETTINGS) return false
        updateSettings(settings.copy(bindings = KeyBindings.DEFAULT))
        return true
    }

    private fun updateSettings(changed: Settings) {
        settings = changed
        settingsLibrary.write(changed)
    }

    // --- Меню перед забегом ---

    /** Из паузы (QUIT RUN), после game over и победы — тоже в ангар: забег окончен, слот уже выбран. */
    fun openHangar(): Boolean = transition(
        from = setOf(
            ScreenState.MENU, ScreenState.LOADOUT, ScreenState.META_SHOP, ScreenState.BESTIARY, ScreenState.PAUSE,
            ScreenState.GAME_OVER, ScreenState.VICTORY,
        ),
        to = ScreenState.HANGAR,
    )

    fun openMetaShop(): Boolean = transition(from = setOf(ScreenState.HANGAR), to = ScreenState.META_SHOP)

    fun openBestiary(): Boolean = transition(from = setOf(ScreenState.HANGAR), to = ScreenState.BESTIARY)

    /** Вид встретился в забеге — его карточка в бестиарии открывается (сохраняется со слотом). */
    fun markSeen(kindId: String) {
        meta = meta.see(kindId)
    }

    /** Выбор открытого корабля ведёт в меню снаряжения. */
    fun openLoadout(ship: ShipType): Boolean {
        if (screen != ScreenState.HANGAR) return false
        meta = meta.selectShip(ship) ?: return false
        screen = ScreenState.LOADOUT
        return true
    }

    fun buyShip(ship: ShipType): Boolean = screen == ScreenState.HANGAR && updateMeta { it.buyShip(ship) }

    fun pickStore(index: Int): Boolean = screen == ScreenState.LOADOUT && updateMeta { it.pickStore(it.selectedShip, index) }

    fun buyGood(counter: Counter, index: Int): Boolean = screen == ScreenState.META_SHOP && updateMeta { it.buyGood(counter, index) }

    // --- Забег ---

    /** Забег на выбранном корабле с выбранным подвесом. */
    fun startRun(): Boolean {
        val allowed = setOf(ScreenState.MENU, ScreenState.LOADOUT, ScreenState.GAME_OVER, ScreenState.VICTORY)
        if (screen !in allowed) return false
        val ship = meta.selectedShip
        val base = ship.stats.baseWeapon
        run = RunState(weapon = base, inventory = PowerUpInventory.loadout(base, meta.storeFor(ship).type))
        entities.clear()
        hazards.clear()
        entities.spawn { newPlayer(it, ship) }
        screen = ScreenState.PLAYING
        return true
    }

    fun tick(dt: Float) {
        run = run.copy(phaseTime = run.phaseTime + dt, effects = run.effects.tick(dt), inventory = run.inventory.tick(dt))
    }

    fun setTrigger(trigger: Trigger) {
        run = run.copy(trigger = trigger)
    }

    fun equip(weapon: WeaponType) {
        if (weapon != run.weapon) run = run.copy(weapon = weapon, trigger = Trigger())
    }

    /** Можно ли сейчас получить предмет: постоянные предметы — всегда (хранилище бесконечно), остальное — если есть место. */
    fun canReceive(type: PowerUpType): Boolean = type is PowerUpType.ItemPickup || run.inventory.add(type) != null

    /** Подбор с поля; при полном инвентаре усилитель не подбирается. */
    fun pickUp(type: PowerUpType): Boolean {
        if (type is PowerUpType.ItemPickup) {
            run = run.copy(storage = run.storage.add(type.item))
            return true
        }
        val inventory = run.inventory.add(type) ?: return false
        run = run.copy(inventory = inventory)
        return true
    }

    /**
     * Оружие из слота становится текущим (и остаётся в слоте); постоянный усилитель включает эффект
     * и уходит на перезарядку; расходник тратится и запускает (или продлевает) эффект.
     */
    fun activateSlot(index: Int): Boolean {
        if (screen != ScreenState.PLAYING && screen != ScreenState.PAUSE) return false
        val (inventory, use) = run.inventory.use(index) ?: return false
        run = run.copy(inventory = inventory)
        when (use) {
            is SlotUse.Equip -> equip(use.weapon)
            is SlotUse.Effect -> run = run.copy(effects = run.effects.activate(use.type, use.duration))
            is SlotUse.Summon -> run = run.copy(summonRequests = run.summonRequests + use.source)
        }
        return true
    }

    /** Очищенное текущее оружие заменяется оружием корабля из первого слота. */
    fun clearSlot(index: Int): Boolean {
        if (screen != ScreenState.PLAYING && screen != ScreenState.PAUSE) return false
        val cleared = run.inventory.slots.getOrNull(index)
        val inventory = run.inventory.clear(index) ?: return false
        run = run.copy(inventory = inventory)
        if (cleared is Slot.Armament && cleared.weapon == run.weapon) {
            (inventory.slots.firstOrNull() as? Slot.Armament)?.let { equip(it.weapon) }
        }
        return true
    }

    /** Покупка в магазине между уровнями: предложение не продано, предмет есть куда положить и хватает денег. */
    fun buy(offerIndex: Int): Boolean {
        val shop = run.shop ?: return false
        if (screen != ScreenState.SHOP) return false
        val offer = shop.offers.getOrNull(offerIndex) ?: return false
        if (offer.sold || !meta.wallet.canAfford(offer.price) || !canReceive(offer.type)) return false
        spend(offer.price)
        pickUp(offer.type)
        run = run.copy(shop = shop.sold(offerIndex))
        return true
    }

    fun pause(): Boolean = transition(from = setOf(ScreenState.PLAYING), to = ScreenState.PAUSE)

    fun resume(): Boolean = transition(from = setOf(ScreenState.PAUSE), to = ScreenState.PLAYING)

    fun gameOver(): Boolean = transition(from = setOf(ScreenState.PLAYING), to = ScreenState.GAME_OVER)

    fun toMenu(): Boolean {
        if (screen == ScreenState.MENU) return false
        screen = ScreenState.MENU
        return true
    }

    /**
     * Следующая фаза прогрессии. В фазе Shop открывается экран магазина;
     * новый уровень начинается с чистого поля — остаётся только корабль (HP переносится).
     */
    fun advancePhase(): Boolean {
        if (screen != ScreenState.PLAYING && screen != ScreenState.SHOP) return false
        val next = run.phase.next()
        val shop = if (next is Phase.Shop) Shop.generate(meta.dropPool, Config.Shop.OFFERS, random) else null
        run = run.copy(phase = next, phaseTime = 0f, trigger = Trigger(), shop = shop)
        if (next is Phase.Level) clearField()
        screen = if (next is Phase.Shop) ScreenState.SHOP else ScreenState.PLAYING
        return true
    }

    /** Награда за босса; после последнего — победа, иначе магазин. */
    fun bossDefeated(money: Int, item: PermanentItem): Boolean {
        if (screen != ScreenState.PLAYING || run.phase !is Phase.BossFight) return false
        earn(money)
        run = run.copy(storage = run.storage.add(item))
        if (run.phase.level >= Config.Progression.LAST_LEVEL) {
            screen = ScreenState.VICTORY
            return true
        }
        return advancePhase()
    }

    fun earn(amount: Int) {
        meta = meta.copy(wallet = meta.wallet.deposit(amount))
    }

    fun spend(amount: Int): Boolean {
        val rest = meta.wallet.withdraw(amount) ?: return false
        meta = meta.copy(wallet = rest)
        return true
    }

    private fun updateMeta(change: (MetaState) -> MetaState?): Boolean {
        meta = change(meta) ?: return false
        return true
    }

    /** Новый уровень: на поле остаются корабль и союзники, которым разрешено переживать уровень. */
    private fun clearField() {
        entities.all.filter { it !is Player && !(it is Npc && it.isAlly && it.summon?.spec?.keepsBetweenLevels == true) }
            .map { it.id }.forEach { entities.remove(it) }
        hazards.clear()
    }

    /** Забирает заказанные призывы (их исполняет игровая логика). */
    fun takeSummonRequests(): List<PowerUpType> {
        val requests = run.summonRequests
        if (requests.isNotEmpty()) run = run.copy(summonRequests = emptyList())
        return requests
    }

    private fun transition(from: Set<ScreenState>, to: ScreenState): Boolean {
        if (screen !in from) return false
        screen = to
        return true
    }

    private companion object {
        val SETTINGS_FROM = setOf(ScreenState.MENU, ScreenState.HANGAR, ScreenState.PAUSE)
        val FIELD_SCREENS = setOf(ScreenState.PLAYING, ScreenState.PAUSE, ScreenState.SHOP, ScreenState.GAME_OVER, ScreenState.VICTORY)
    }
}

// --- Этапы забега ---

/** Прогрессия забега: Level(n) → BossFight(n) → Shop(n) → Level(n + 1). */
sealed interface Phase {
    val level: Int
    

    data class Level(override val level: Int) : Phase
    data class BossFight(override val level: Int) : Phase
    data class Shop(override val level: Int) : Phase
    //data class (override val level: Int) : Phase

    fun next(): Phase = when (this) {
        is Level -> BossFight(level)
        is BossFight -> Shop(level)
        is Shop -> Level(level + 1)
    }

    companion object {
        const val FIRST_LEVEL = 1
        val START: Phase = Level(FIRST_LEVEL)
    }
}

// --- Реестр сущностей ---

/**
 * Единственное место хранения сущностей. Снимки неизменяемы, поэтому любое изменение —
 * это [replace] новым снимком с тем же id. Id не переиспользуются даже после [clear],
 * чтобы устаревшая ссылка (например, из userData Box2D-тела) не попала на чужую сущность.
 */
class EntityRegistry {
    private val entities = LinkedHashMap<EntityId, Entity>()
    private var nextId = 1

    /** Живое представление; при изменении реестра во время обхода — снимать копию через toList(). */
    val all: Collection<Entity> get() = entities.values
    val size: Int get() = entities.size

    operator fun get(id: EntityId): Entity? = entities[id]
    operator fun contains(id: EntityId): Boolean = id in entities

    fun <T : Entity> spawn(create: (EntityId) -> T): T {
        val id = EntityId(nextId++)
        val entity = create(id)
        require(entity.id == id) { "factory must use the provided id $id, got ${entity.id}" }
        entities[id] = entity
        return entity
    }

    fun replace(entity: Entity) {
        val previous = requireNotNull(entities[entity.id]) { "unknown entity ${entity.id}" }
        require(previous::class == entity::class) { "entity ${entity.id} cannot change type" }
        entities[entity.id] = entity
    }

    fun remove(id: EntityId): Entity? = entities.remove(id)

    fun clear() = entities.clear()

    inline fun <reified T : Entity> ofType(): List<T> = all.filterIsInstance<T>()
}
