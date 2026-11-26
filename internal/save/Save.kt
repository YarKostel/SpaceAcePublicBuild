package com.spaceace.game.save

import com.badlogic.gdx.Input.Keys
import com.badlogic.gdx.Preferences
import com.spaceace.game.Config
import com.spaceace.game.input.Control
import com.spaceace.game.input.KeyBindings
import com.spaceace.game.model.Perk
import com.spaceace.game.model.PowerUpType
import com.spaceace.game.model.ShipType
import com.spaceace.game.model.WeaponType
import com.spaceace.game.state.GameState
import com.spaceace.game.state.MetaState
import com.spaceace.game.state.SaveLibrary
import com.spaceace.game.state.ScreenState
import com.spaceace.game.state.Settings
import com.spaceace.game.state.SettingsLibrary
import com.spaceace.game.state.Wallet
import com.spaceace.game.state.slotsOf

// --- Хранилище ---

/** Минимальное key-value хранилище: на устройстве — Preferences, в тестах — Map. */
interface KeyValueStore {
    fun getInt(key: String, default: Int): Int
    fun putInt(key: String, value: Int)
    fun getString(key: String, default: String): String
    fun putString(key: String, value: String)
    /** Стирает все ключи; на диск — при [flush]. */
    fun clear()
    fun flush()
}

/** Desktop: файл в %USERPROFILE%/.prefs/; Android: SharedPreferences приложения. */
class PreferencesStore(private val preferences: Preferences) : KeyValueStore {
    override fun getInt(key: String, default: Int): Int = preferences.getInteger(key, default)

    override fun putInt(key: String, value: Int) {
        preferences.putInteger(key, value)
    }

    override fun getString(key: String, default: String): String = preferences.getString(key, default)

    override fun putString(key: String, value: String) {
        preferences.putString(key, value)
    }

    override fun clear() = preferences.clear()

    override fun flush() = preferences.flush()
}

// --- Мета-состояние ---

/**
 * Сохраняет и загружает то, что переживает забег: деньги и всё открытое в меню перед забегом.
 * Корабли и подвесы пишутся стабильными строковыми id; неизвестные id при загрузке пропускаются,
 * чтобы переименование или удаление контента не ломало старые сохранения.
 */
class MetaStore(private val store: KeyValueStore) {
    fun hasSave(): Boolean = store.getInt(Config.Save.KEY_VERSION, NO_SAVE) != NO_SAVE

    fun load(): MetaState {
        val version = store.getInt(Config.Save.KEY_VERSION, NO_SAVE)
        if (version == NO_SAVE) return MetaState()
        // Повреждённое значение не должно ронять игру при запуске.
        val wallet = Wallet(store.getInt(Config.Save.KEY_MONEY, 0).coerceAtLeast(0))
        if (version < UNLOCKS_VERSION) return MetaState(wallet)
        return MetaState(
            wallet = wallet,
            ships = list(Config.Save.KEY_SHIPS).mapNotNull(::shipOf).toSet(),
            stores = list(Config.Save.KEY_STORES).mapNotNull(::storeOf).toSet(),
            selectedShip = shipOf(store.getString(Config.Save.KEY_SELECTED_SHIP, "")) ?: ShipType.Rebel,
            selectedStores = list(Config.Save.KEY_SELECTED_STORES).mapNotNull(::selectionOf).toMap(),
            items = list(Config.Save.KEY_ITEMS).mapNotNull(String::toIntOrNull).toSet(),
            storeGoods = list(Config.Save.KEY_STORE_GOODS).mapNotNull(String::toIntOrNull).toSet(),
            // До v3 бестиария не было: все карточки закрыты.
            seen = if (version < BESTIARY_VERSION) emptySet() else list(Config.Save.KEY_SEEN).toSet(),
        )
    }

    fun save(meta: MetaState) {
        store.putInt(Config.Save.KEY_VERSION, Config.Save.VERSION)
        store.putInt(Config.Save.KEY_MONEY, meta.wallet.balance)
        store.putString(Config.Save.KEY_SHIPS, meta.ships.map { it.id }.sorted().joinToString(LIST))
        store.putString(Config.Save.KEY_STORES, meta.stores.mapNotNull(::storeId).sorted().joinToString(LIST))
        store.putString(Config.Save.KEY_SELECTED_SHIP, meta.selectedShip.id)
        store.putString(
            Config.Save.KEY_SELECTED_STORES,
            meta.selectedStores.mapNotNull { (ship, type) -> storeId(type)?.let { "${ship.id}$PAIR$it" } }.sorted().joinToString(LIST),
        )
        store.putString(Config.Save.KEY_ITEMS, meta.items.sorted().joinToString(LIST))
        store.putString(Config.Save.KEY_STORE_GOODS, meta.storeGoods.sorted().joinToString(LIST))
        store.putString(Config.Save.KEY_SEEN, meta.seen.sorted().joinToString(LIST))
        store.flush()
    }

    private fun list(key: String): List<String> = store.getString(key, "").split(LIST).filter { it.isNotBlank() }

    private fun selectionOf(entry: String): Pair<ShipType, PowerUpType>? {
        val (ship, type) = entry.split(PAIR).takeIf { it.size == 2 } ?: return null
        return (shipOf(ship) ?: return null) to (storeOf(type) ?: return null)
    }

    private companion object {
        const val NO_SAVE = -1
        /** С этой версии в файле есть открытые корабли, подвесы и товары. */
        const val UNLOCKS_VERSION = 2
        /** С этой версии в файле есть встреченные виды (бестиарий). */
        const val BESTIARY_VERSION = 3
        const val LIST = ","
        const val PAIR = "="
    }
}

fun shipOf(id: String): ShipType? = ShipType.ALL.firstOrNull { it.id == id }

/** Id подвеса в сохранении: "weapon:RICOCHET", "perk:SHIELD_GENERATOR"; у прочих усилителей id нет. */
fun storeId(type: PowerUpType): String? = when (type) {
    is PowerUpType.WeaponPickup -> "$WEAPON_PREFIX${type.weapon.name}"
    is PowerUpType.PerkPickup -> "$PERK_PREFIX${type.perk.name}"
    else -> null
}

fun storeOf(id: String): PowerUpType? = when {
    id.startsWith(WEAPON_PREFIX) -> WeaponType.entries.firstOrNull { it.name == id.removePrefix(WEAPON_PREFIX) }?.let { PowerUpType.WeaponPickup(it) }
    id.startsWith(PERK_PREFIX) -> Perk.entries.firstOrNull { it.name == id.removePrefix(PERK_PREFIX) }?.let { PowerUpType.PerkPickup(it) }
    else -> null
}

private const val WEAPON_PREFIX = "weapon:"
private const val PERK_PREFIX = "perk:"

/**
 * Пишет в выбранный слот, только когда мета-состояние изменилось и игра не в процессе боя (меню, пауза, магазин,
 * game over) — чтобы не трогать файл на каждом сбитом метеорите. [flush] — принудительно, при сворачивании и закрытии.
 * Пока слот не выбран (главное меню, экран выбора), писать некуда.
 */
class AutoSave(private val state: GameState, private val saves: SaveLibrary) {
    private var savedSlot: Int? = null
    private var saved: MetaState? = null

    fun update() {
        if (state.screen != ScreenState.PLAYING) flush()
    }

    fun flush() {
        val slot = state.activeSlot ?: return
        if (slot == savedSlot && state.meta == saved) return
        saves.write(slot, state.meta)
        savedSlot = slot
        saved = state.meta
    }
}

// --- Слоты ---

/**
 * Слоты сохранений на диске: каждый слот — своё хранилище "spaceace-save-N", список занятых — в индексе
 * (Preferences не умеют перечислять файлы). [open] — хранилище по имени: Preferences на устройстве, Map в тестах.
 * Старое единственное сохранение один раз переносится в слот 0.
 */
class SaveSlots(private val open: (String) -> KeyValueStore) : SaveLibrary {
    private val index = open(Config.Save.INDEX_NAME)

    init {
        migrateLegacy()
    }

    override fun read(): List<MetaState?> = slotsOf(occupied().associateWith { MetaStore(open(slotName(it))).load() })

    override fun write(slot: Int, meta: MetaState) {
        MetaStore(open(slotName(slot))).save(meta)
        val slots = occupied()
        if (slot !in slots) setOccupied(slots + slot)
    }

    override fun delete(slot: Int) {
        open(slotName(slot)).run {
            clear()
            flush()
        }
        setOccupied(occupied() - slot)
    }

    private fun occupied(): Set<Int> =
        index.getString(Config.Save.KEY_SLOTS, "").split(LIST).mapNotNull(String::toIntOrNull).filter { it >= 0 }.toSet()

    private fun setOccupied(slots: Set<Int>) {
        index.putString(Config.Save.KEY_SLOTS, slots.sorted().joinToString(LIST))
        index.flush()
    }

    private fun migrateLegacy() {
        if (index.getInt(Config.Save.KEY_MIGRATED, 0) == MIGRATED) return
        val legacy = MetaStore(open(Config.Save.LEGACY_PREFERENCES_NAME))
        if (legacy.hasSave() && FIRST_SLOT !in occupied()) write(FIRST_SLOT, legacy.load())
        index.putInt(Config.Save.KEY_MIGRATED, MIGRATED)
        index.flush()
    }

    private companion object {
        const val LIST = ","
        const val MIGRATED = 1
        const val FIRST_SLOT = 0
    }
}

/** Имя хранилища слота: номера в интерфейсе и в файлах — с 1. */
fun slotName(slot: Int): String = "${Config.Save.SLOT_PREFIX}${slot + 1}"

// --- Настройки ---

/**
 * Настройки в отдельном файле (Preferences [Config.Save.SETTINGS_NAME]): клавиша каждого действия — "key.<ДЕЙСТВИЕ>",
 * [UNBOUND] — без клавиши. Нет ключа или он испорчен — клавиша по умолчанию; файла нет — вся раскладка по умолчанию.
 */
class SettingsStore(private val store: KeyValueStore) : SettingsLibrary {
    override fun read(): Settings {
        if (store.getInt(Config.Save.KEY_VERSION, MISSING) == MISSING) return Settings()
        val keys = LinkedHashMap<Control, Int>()
        for (control in Control.entries) {
            val default = KeyBindings.DEFAULT.keyOf(control) ?: UNBOUND
            val key = store.getInt(keyOf(control), default).takeIf { it == UNBOUND || it in 0..Keys.MAX_KEYCODE } ?: default
            // Повреждённый файл с одной клавишей у двух действий: клавиша остаётся у первого.
            if (key != UNBOUND && key !in keys.values) keys[control] = key
        }
        return Settings(KeyBindings(keys))
    }

    override fun write(settings: Settings) {
        store.clear()
        store.putInt(Config.Save.KEY_VERSION, Config.Save.SETTINGS_VERSION)
        for (control in Control.entries) store.putInt(keyOf(control), settings.bindings.keyOf(control) ?: UNBOUND)
        store.flush()
    }

    private fun keyOf(control: Control) = Config.Save.KEY_BINDING_PREFIX + control.name

    private companion object {
        const val UNBOUND = -1
        const val MISSING = -2
    }
}
