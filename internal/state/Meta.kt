package com.spaceace.game.state

import com.spaceace.game.Config
import com.spaceace.game.input.KeyBindings
import com.spaceace.game.model.BASE_DROPS
import com.spaceace.game.model.PermanentItem
import com.spaceace.game.model.PowerUpType
import com.spaceace.game.model.ShipType
import com.spaceace.game.model.StoreOption

// --- Мета-состояние ---

/** Прилавки магазина в меню. */
enum class Counter { ITEMS, STORES }

/**
 * Всё, что переживает забег (роглайк) и сохраняется в файл. Покупки и выбор — чистые методы:
 * возвращают новое состояние или null, если действие невозможно.
 *
 * @property ships купленные корабли (первый в [ShipType.ALL] с ценой 0 открыт всегда).
 * @property stores купленные подвесы; стартовый подвес корабля открыт всегда.
 * @property selectedStores выбранный подвес для каждого корабля; нет записи — стартовый.
 * @property items купленные заглушки прилавка ITEMS (номера в Config.MetaShop.ITEMS).
 * @property storeGoods купленные заглушки прилавка STORES (номера в Config.MetaShop.STORES).
 * @property seen id видов противников и союзников, уже встреченных в забегах (открытые карточки бестиария).
 */
data class MetaState(
    val wallet: Wallet = Wallet(),
    val ships: Set<ShipType> = emptySet(),
    val stores: Set<PowerUpType> = emptySet(),
    val selectedShip: ShipType = ShipType.Rebel,
    val selectedStores: Map<ShipType, PowerUpType> = emptyMap(),
    val items: Set<Int> = emptySet(),
    val storeGoods: Set<Int> = emptySet(),
    val seen: Set<String> = emptySet(),
) {
    /** Вид встречен — карточка бестиария открывается; повторная встреча ничего не меняет. */
    fun see(kindId: String): MetaState = if (kindId in seen) this else copy(seen = seen + kindId)

    fun owns(ship: ShipType) = ship.stats.price == 0 || ship in ships

    fun owns(ship: ShipType, store: StoreOption) = store.price == 0 || (store in ship.stats.stores && store.type in stores)

    fun owns(counter: Counter, index: Int) = index in goods(counter)

    /** Подвес, с которым корабль выйдет в забег. */
    fun storeFor(ship: ShipType): StoreOption {
        val options = ship.stats.stores
        val chosen = selectedStores[ship]?.let { type -> options.firstOrNull { it.type == type } }
        return chosen?.takeIf { owns(ship, it) } ?: options.first()
    }

    /** Что выпадает в забеге: базовые усилители и всё купленное в магазине меню. */
    val dropPool: List<PowerUpType>
        get() = BASE_DROPS +
            storeGoods.sorted().map { PowerUpType.StoreStub(it) } +
            items.sorted().map { PowerUpType.ItemPickup(Config.MetaShop.ITEMS[it].item ?: PermanentItem.Stub(it)) }

    fun buyShip(ship: ShipType): MetaState? {
        if (owns(ship)) return null
        val rest = wallet.withdraw(ship.stats.price) ?: return null
        return copy(wallet = rest, ships = ships + ship)
    }

    fun selectShip(ship: ShipType): MetaState? = if (owns(ship)) copy(selectedShip = ship) else null

    /** Открытый подвес выбирается; закрытый покупается и сразу выбирается. */
    fun pickStore(ship: ShipType, index: Int): MetaState? {
        val option = ship.stats.stores.getOrNull(index) ?: return null
        val paid = if (owns(ship, option)) this else {
            val rest = wallet.withdraw(option.price) ?: return null
            copy(wallet = rest, stores = stores + option.type)
        }
        return paid.copy(selectedStores = paid.selectedStores + (ship to option.type))
    }

    fun buyGood(counter: Counter, index: Int): MetaState? {
        val good = catalog(counter).getOrNull(index) ?: return null
        if (owns(counter, index)) return null
        val rest = wallet.withdraw(good.price) ?: return null
        return when (counter) {
            Counter.ITEMS -> copy(wallet = rest, items = items + index)
            Counter.STORES -> copy(wallet = rest, storeGoods = storeGoods + index)
        }
    }

    private fun goods(counter: Counter) = when (counter) {
        Counter.ITEMS -> items
        Counter.STORES -> storeGoods
    }

    companion object {
        fun catalog(counter: Counter) = when (counter) {
            Counter.ITEMS -> Config.MetaShop.ITEMS
            Counter.STORES -> Config.MetaShop.STORES
        }
    }
}

// --- Кошелёк ---

data class Wallet(val balance: Int = 0) {
    init {
        require(balance >= 0) { "balance must be non-negative: $balance" }
    }

    fun canAfford(price: Int): Boolean = price in 0..balance

    fun deposit(amount: Int): Wallet {
        require(amount >= 0) { "deposit must be non-negative: $amount" }
        return Wallet(Math.addExact(balance, amount))
    }

    /** null — денег не хватает. */
    fun withdraw(amount: Int): Wallet? {
        require(amount >= 0) { "withdraw must be non-negative: $amount" }
        return if (canAfford(amount)) Wallet(balance - amount) else null
    }
}

// --- Слоты сохранений ---

/** Слоты сохранений (номера с 0). На устройстве — файлы (SaveSlots в save/Save.kt), в тестах — память. */
interface SaveLibrary {
    /** Содержимое слотов по порядку для экрана выбора; null — пустой слот. */
    fun read(): List<MetaState?>
    fun write(slot: Int, meta: MetaState)
    fun delete(slot: Int)
}

/**
 * Слоты для экрана выбора: не меньше [minimum]; если все слоты до последнего занятого заняты,
 * в конце добавляется ещё один пустой — для новой игры.
 */
fun slotsOf(saved: Map<Int, MetaState>, minimum: Int = Config.Save.DEFAULT_SLOTS): List<MetaState?> {
    val last = saved.keys.maxOrNull() ?: -1
    val full = (0..last).all { it in saved }
    val count = maxOf(minimum, if (full) last + 2 else last + 1)
    return List(count) { saved[it] }
}

/** Слоты в памяти: по умолчанию в [GameState] (тесты, бот) — без файлов. */
class MemorySaves(initial: Map<Int, MetaState> = emptyMap()) : SaveLibrary {
    private val saved = initial.toMutableMap()

    override fun read(): List<MetaState?> = slotsOf(saved)

    override fun write(slot: Int, meta: MetaState) {
        saved[slot] = meta
    }

    override fun delete(slot: Int) {
        saved.remove(slot)
    }
}

// --- Настройки ---

/** Настройки игры — общие для всех слотов сохранения. */
data class Settings(val bindings: KeyBindings = KeyBindings.DEFAULT)

interface SettingsLibrary {
    fun read(): Settings
    fun write(settings: Settings)
}

/** Настройки в памяти — для тестов и бота. */
class MemorySettings(private var stored: Settings = Settings()) : SettingsLibrary {
    override fun read(): Settings = stored

    override fun write(settings: Settings) {
        stored = settings
    }
}
