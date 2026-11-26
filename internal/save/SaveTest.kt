package com.spaceace.game.save

import com.badlogic.gdx.Input.Keys
import com.spaceace.game.Config
import com.spaceace.game.input.Control
import com.spaceace.game.input.KeyBindings
import com.spaceace.game.model.Perk
import com.spaceace.game.model.PowerUpType
import com.spaceace.game.model.ShipType
import com.spaceace.game.model.WeaponType
import com.spaceace.game.state.GameState
import com.spaceace.game.state.MetaState
import com.spaceace.game.state.Settings
import com.spaceace.game.state.Wallet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class MapStore : KeyValueStore {
    val ints = HashMap<String, Int>()
    val strings = HashMap<String, String>()
    var flushes = 0

    override fun getInt(key: String, default: Int) = ints[key] ?: default
    override fun putInt(key: String, value: Int) {
        ints[key] = value
    }
    override fun getString(key: String, default: String) = strings[key] ?: default
    override fun putString(key: String, value: String) {
        strings[key] = value
    }
    override fun clear() {
        ints.clear()
        strings.clear()
    }
    override fun flush() {
        flushes++
    }
}

class MetaStoreTest {
    @Test
    fun `no save gives a fresh meta state`() {
        assertEquals(MetaState(), MetaStore(MapStore()).load())
    }

    @Test
    fun `money and unlocks survive save and load`() {
        val kv = MapStore()
        val meta = MetaState(
            wallet = Wallet(137),
            ships = setOf(ShipType.Glory),
            stores = setOf(PowerUpType.WeaponPickup(WeaponType.RICOCHET), PowerUpType.PerkPickup(Perk.SHIELD_GENERATOR)),
            selectedShip = ShipType.Glory,
            selectedStores = mapOf(ShipType.Glory to PowerUpType.PerkPickup(Perk.SHIELD_GENERATOR)),
            items = setOf(0, 2),
            storeGoods = setOf(1),
        )
        MetaStore(kv).save(meta)
        assertEquals(meta, MetaStore(kv).load())
        assertEquals(Config.Save.VERSION, kv.ints[Config.Save.KEY_VERSION])
        assertEquals(1, kv.flushes)
    }

    @Test
    fun `met kinds survive save and load, a version 2 file has none`() {
        val kv = MapStore()
        MetaStore(kv).save(MetaState(Wallet(5), seen = setOf("gravel", "wasp-drone")))
        assertEquals(setOf("gravel", "wasp-drone"), MetaStore(kv).load().seen)
        assertEquals(3, Config.Save.VERSION)

        val old = MapStore()
        old.ints[Config.Save.KEY_VERSION] = 2
        old.ints[Config.Save.KEY_MONEY] = 7
        old.strings[Config.Save.KEY_SEEN] = "gravel"
        val loaded = MetaStore(old).load()
        assertEquals(7, loaded.wallet.balance)
        assertTrue(loaded.seen.isEmpty())
    }

    @Test
    fun `version 1 save keeps its money`() {
        val kv = MapStore()
        kv.ints[Config.Save.KEY_VERSION] = 1
        kv.ints[Config.Save.KEY_MONEY] = 42
        assertEquals(MetaState(Wallet(42)), MetaStore(kv).load())
    }

    @Test
    fun `corrupted values load safely`() {
        val kv = MapStore()
        kv.ints[Config.Save.KEY_VERSION] = Config.Save.VERSION
        kv.ints[Config.Save.KEY_MONEY] = -50
        kv.strings[Config.Save.KEY_SHIPS] = "glory,unknown-ship,"
        kv.strings[Config.Save.KEY_STORES] = "weapon:PLASMA,perk:SHIELD_GENERATOR"
        kv.strings[Config.Save.KEY_SELECTED_SHIP] = "nope"
        kv.strings[Config.Save.KEY_SELECTED_STORES] = "glory=weapon:IR_MISSILES,broken,rebel=weapon:NONE"
        kv.strings[Config.Save.KEY_ITEMS] = "1,x,2"
        val meta = MetaStore(kv).load()
        assertEquals(0, meta.wallet.balance)
        assertEquals(setOf(ShipType.Glory), meta.ships)
        assertEquals(setOf<PowerUpType>(PowerUpType.PerkPickup(Perk.SHIELD_GENERATOR)), meta.stores)
        assertEquals(ShipType.Rebel, meta.selectedShip)
        assertEquals(mapOf<ShipType, PowerUpType>(ShipType.Glory to PowerUpType.WeaponPickup(WeaponType.IR_MISSILES)), meta.selectedStores)
        assertEquals(setOf(1, 2), meta.items)
    }

    @Test
    fun `store ids round trip and other power-ups have none`() {
        for (type in listOf(PowerUpType.WeaponPickup(WeaponType.GATLING), PowerUpType.PerkPickup(Perk.HEAVY_ROUNDS_SUPPLY))) {
            assertEquals(type, storeOf(storeId(type)!!))
        }
        assertNull(storeId(PowerUpType.Shield))
        assertNull(storeOf("garbage"))
    }
}

class AutoSaveTest {
    private val files = HashMap<String, MapStore>()
    private val saves = SaveSlots { files.getOrPut(it) { MapStore() } }
    private val state = GameState(saves = saves)
    private val autoSave = AutoSave(state, saves)
    private val slotFile: MapStore get() = files.getValue(slotName(0))

    private fun chooseFirstSlot() {
        state.openSaves()
        state.chooseSave(0)
        autoSave.update()
    }

    private fun saved(): MetaState = assertNotNull(saves.read()[0])

    @Test
    fun `nothing is written before a slot is chosen`() {
        state.earn(10)
        autoSave.update()
        autoSave.flush()
        assertTrue(files.keys.none { it.startsWith(Config.Save.SLOT_PREFIX) })
    }

    @Test
    fun `nothing is written while meta is unchanged`() {
        chooseFirstSlot()
        val flushes = slotFile.flushes
        autoSave.update()
        autoSave.flush()
        assertEquals(flushes, slotFile.flushes)
    }

    @Test
    fun `money earned in battle is written only when the battle stops`() {
        chooseFirstSlot()
        val flushes = slotFile.flushes
        state.openLoadout(ShipType.Rebel)
        state.startRun()
        state.earn(25)
        autoSave.update()
        assertEquals(flushes, slotFile.flushes)

        state.gameOver()
        autoSave.update()
        assertEquals(flushes + 1, slotFile.flushes)
        assertEquals(25, saved().wallet.balance)

        autoSave.update()
        assertEquals(flushes + 1, slotFile.flushes)
    }

    @Test
    fun `purchases in the menus are saved right away`() {
        chooseFirstSlot()
        state.earn(500)
        state.buyShip(ShipType.Glory)
        autoSave.update()
        assertEquals(setOf(ShipType.Glory), saved().ships)
    }

    @Test
    fun `forced flush writes during battle`() {
        chooseFirstSlot()
        state.openLoadout(ShipType.Rebel)
        state.startRun()
        state.earn(5)
        autoSave.flush()
        assertEquals(5, saved().wallet.balance)
    }

    @Test
    fun `deleted active slot is not resurrected`() {
        chooseFirstSlot()
        state.earn(5)
        autoSave.update()
        state.openSaves()
        assertTrue(state.deleteSave(0))
        autoSave.update()
        assertNull(saves.read()[0])
    }
}

class SaveSlotsTest {
    private val files = HashMap<String, MapStore>()
    private fun slots() = SaveSlots { files.getOrPut(it) { MapStore() } }

    @Test
    fun `fresh install has the default number of empty slots`() {
        assertEquals(List<MetaState?>(Config.Save.DEFAULT_SLOTS) { null }, slots().read())
    }

    @Test
    fun `slots survive a restart and live in separate files`() {
        slots().write(1, MetaState(Wallet(70)))
        val read = slots().read()
        assertEquals(MetaState(Wallet(70)), read[1])
        assertNull(read[0])
        assertTrue(slotName(1) in files)
        assertEquals("spaceace-save-2", slotName(1))
    }

    @Test
    fun `an extra slot appears when all are taken`() {
        val saves = slots()
        repeat(Config.Save.DEFAULT_SLOTS) { saves.write(it, MetaState(Wallet(it))) }
        val read = saves.read()
        assertEquals(Config.Save.DEFAULT_SLOTS + 1, read.size)
        assertNull(read.last())
    }

    @Test
    fun `deleting clears the file and frees the slot`() {
        val saves = slots()
        repeat(4) { saves.write(it, MetaState(Wallet(10))) }
        saves.delete(1)
        assertTrue(files.getValue(slotName(1)).ints.isEmpty())
        val read = slots().read()
        assertNull(read[1])
        assertEquals(4, read.size, "no extra slot while one is free")
    }

    @Test
    fun `legacy single save moves to the first slot once`() {
        MetaStore(files.getOrPut(Config.Save.LEGACY_PREFERENCES_NAME) { MapStore() }).save(MetaState(Wallet(32)))
        val saves = slots()
        assertEquals(MetaState(Wallet(32)), saves.read()[0])
        saves.delete(0)
        assertNull(slots().read()[0], "deleted slot must not be migrated again")
    }

    @Test
    fun `corrupted index entries are ignored`() {
        files.getOrPut(Config.Save.INDEX_NAME) { MapStore() }.strings[Config.Save.KEY_SLOTS] = "x,-3,"
        assertEquals(Config.Save.DEFAULT_SLOTS, slots().read().size)
    }
}

class SettingsStoreTest {
    private val file = MapStore()
    private val store = SettingsStore(file)

    @Test
    fun `no settings file means the default layout`() {
        assertEquals(Settings(), store.read())
    }

    @Test
    fun `bindings survive a restart, including a control without a key`() {
        val bindings = KeyBindings.DEFAULT.bind(Control.FIRE, Keys.A)
        store.write(Settings(bindings))
        assertTrue(file.flushes > 0)
        val loaded = SettingsStore(file).read().bindings
        assertEquals(bindings, loaded)
        assertNull(loaded.keyOf(Control.SLOT_1))
    }

    @Test
    fun `missing or broken key falls back to the default`() {
        store.write(Settings())
        file.ints.remove(Config.Save.KEY_BINDING_PREFIX + Control.PAUSE.name)
        file.ints[Config.Save.KEY_BINDING_PREFIX + Control.FIRE.name] = Keys.MAX_KEYCODE + 10
        val loaded = store.read().bindings
        assertEquals(Keys.P, loaded.keyOf(Control.PAUSE))
        assertEquals(Keys.S, loaded.keyOf(Control.FIRE))
    }

    @Test
    fun `a key written for two controls stays with the first one`() {
        store.write(Settings())
        file.ints[Config.Save.KEY_BINDING_PREFIX + Control.PAUSE.name] = Keys.S
        val loaded = store.read().bindings
        assertEquals(Control.FIRE, loaded.controlOf(Keys.S))
        assertNull(loaded.keyOf(Control.PAUSE))
    }
}
