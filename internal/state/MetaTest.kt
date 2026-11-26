package com.spaceace.game.state

import com.spaceace.game.Config
import com.spaceace.game.model.BASE_DROPS
import com.spaceace.game.model.Perk
import com.spaceace.game.model.PermanentItem
import com.spaceace.game.model.PowerUpType
import com.spaceace.game.model.ShipType
import com.spaceace.game.model.WeaponType
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetaStateTest {
    private val rich = MetaState(Wallet(1000))

    @Test
    fun `only the free ship is open from the start`() {
        val meta = MetaState()
        assertTrue(meta.owns(ShipType.Rebel))
        assertFalse(meta.owns(ShipType.Glory))
        assertNull(meta.selectShip(ShipType.Glory))
    }

    @Test
    fun `buying a ship costs its price and only works once`() {
        val bought = assertNotNull(rich.buyShip(ShipType.Glory))
        assertEquals(1000 - ShipType.Glory.stats.price, bought.wallet.balance)
        assertTrue(bought.owns(ShipType.Glory))
        assertNull(bought.buyShip(ShipType.Glory))
        assertNull(MetaState().buyShip(ShipType.Glory))
    }

    @Test
    fun `starter store is chosen by default and others must be bought`() {
        val ship = ShipType.Rebel
        val (starter, locked) = ship.stats.stores
        assertEquals(starter, rich.storeFor(ship))
        assertFalse(rich.owns(ship, locked))
        assertNull(MetaState().pickStore(ship, 1))

        val bought = assertNotNull(rich.pickStore(ship, 1))
        assertEquals(1000 - locked.price, bought.wallet.balance)
        assertEquals(locked, bought.storeFor(ship))

        val back = assertNotNull(bought.pickStore(ship, 0))
        assertEquals(starter, back.storeFor(ship))
        assertEquals(bought.wallet, assertNotNull(back.pickStore(ship, 1)).wallet, "owned store is free to pick again")
    }

    @Test
    fun `each ship has its own stores`() {
        assertEquals(PowerUpType.PerkPickup(Perk.HEAVY_ROUNDS_SUPPLY), ShipType.Rebel.stats.stores[0].type)
        assertEquals(PowerUpType.WeaponPickup(WeaponType.RICOCHET), ShipType.Rebel.stats.stores[1].type)
        assertEquals(PowerUpType.WeaponPickup(WeaponType.IR_MISSILES), ShipType.Glory.stats.stores[0].type)
        assertEquals(PowerUpType.PerkPickup(Perk.SHIELD_GENERATOR), ShipType.Glory.stats.stores[1].type)
        assertNull(rich.pickStore(ShipType.Rebel, 5))
    }

    @Test
    fun `shop goods are bought once and join the drop pool`() {
        assertEquals(BASE_DROPS, MetaState().dropPool)
        val meta = assertNotNull(rich.buyGood(Counter.ITEMS, 1)?.buyGood(Counter.STORES, 0))
        assertNull(meta.buyGood(Counter.ITEMS, 1))
        assertEquals(
            BASE_DROPS + PowerUpType.StoreStub(0) + PowerUpType.ItemPickup(PermanentItem.Stub(1)),
            meta.dropPool,
        )
        val price = Config.MetaShop.ITEMS[1].price + Config.MetaShop.STORES[0].price
        assertEquals(1000 - price, meta.wallet.balance)
        assertNull(MetaState().buyGood(Counter.ITEMS, 0))
        assertNull(rich.buyGood(Counter.ITEMS, 99))
    }
}

class PreRunFlowTest {
    @Test
    fun `menu, hangar, loadout and back`() {
        val state = GameState(MetaState(Wallet(500)))
        assertTrue(state.openHangar())
        assertFalse(state.openLoadout(ShipType.Glory), "locked ship")
        assertTrue(state.buyShip(ShipType.Glory))
        assertTrue(state.openLoadout(ShipType.Glory))
        assertEquals(ScreenState.LOADOUT, state.screen)
        assertTrue(state.openHangar())
        assertTrue(state.openMetaShop())
        assertTrue(state.openHangar())
        assertTrue(state.toMenu())
    }

    @Test
    fun `run starts with the chosen ship and store`() {
        val state = GameState(MetaState(Wallet(500)))
        state.openHangar()
        state.buyShip(ShipType.Glory)
        state.openLoadout(ShipType.Glory)
        assertTrue(state.pickStore(1))
        assertTrue(state.startRun())
        assertEquals(ShipType.Glory, state.player?.ship)
        assertEquals(WeaponType.GATLING, state.run.weapon)
        assertEquals(Slot.Gear(Perk.SHIELD_GENERATOR), state.run.inventory.slots[1])
    }

    @Test
    fun `menu purchases only work on their screens`() {
        val state = GameState(MetaState(Wallet(500)))
        assertFalse(state.buyShip(ShipType.Glory))
        assertFalse(state.buyGood(Counter.ITEMS, 0))
        assertFalse(state.pickStore(0))
        assertEquals(500, state.meta.wallet.balance)
    }

    @Test
    fun `bought stubs drop in runs and items go to storage`() {
        val state = GameState(MetaState(Wallet(500)), Random(1))
        state.openHangar()
        state.openMetaShop()
        state.buyGood(Counter.ITEMS, 0)
        state.buyGood(Counter.STORES, 0)
        state.toMenu()
        state.startRun()
        assertTrue(state.pickUp(PowerUpType.ItemPickup(PermanentItem.Stub(0))))
        assertEquals(listOf<PermanentItem>(PermanentItem.Stub(0)), state.run.storage.items)
        assertTrue(state.pickUp(PowerUpType.StoreStub(0)))
        assertIs<Slot.Placeholder>(state.run.inventory.slots[2])
        assertFalse(state.activateSlot(2), "stubs have no effect yet")
    }

    @Test
    fun `run shop sells from the drop pool`() {
        val state = GameState(MetaState(Wallet(500), storeGoods = setOf(0, 1, 2), items = setOf(0, 1, 2)), Random(3))
        state.startRun()
        state.advancePhase()
        state.advancePhase()
        val offers = assertNotNull(state.run.shop).offers.map { it.type }
        assertTrue(offers.all { it in state.meta.dropPool })
    }
}

class SlotsOfTest {
    private val meta = MetaState(Wallet(1))

    @Test
    fun `empty library shows the minimum`() {
        assertEquals(listOf<MetaState?>(null, null, null), slotsOf(emptyMap(), minimum = 3))
    }

    @Test
    fun `free slot inside the range means no extra slot`() {
        assertEquals(listOf(meta, null, meta), slotsOf(mapOf(0 to meta, 2 to meta), minimum = 3))
        assertEquals(listOf(meta, meta, null), slotsOf(mapOf(0 to meta, 1 to meta), minimum = 3))
    }

    @Test
    fun `all taken adds one empty slot at the end`() {
        assertEquals(listOf(meta, meta, meta, null), slotsOf(mapOf(0 to meta, 1 to meta, 2 to meta), minimum = 3))
        val five = (0..4).associateWith { meta }
        assertEquals(6, slotsOf(five, minimum = 3).size)
    }
}

class SaveFlowTest {
    private val saved = MetaState(Wallet(300), ships = setOf(ShipType.Glory), selectedShip = ShipType.Glory)

    @Test
    fun `menu leads to save selection, a slot leads to the hangar`() {
        val state = GameState(saves = MemorySaves(mapOf(1 to saved)))
        assertTrue(state.openSaves())
        assertEquals(ScreenState.SAVES, state.screen)
        assertEquals(listOf(null, saved, null), state.slots)
        assertTrue(state.chooseSave(1))
        assertEquals(ScreenState.HANGAR, state.screen)
        assertEquals(saved, state.meta)
        assertEquals(1, state.activeSlot)
    }

    @Test
    fun `empty slot starts a new game and creates the save at once`() {
        val saves = MemorySaves()
        val state = GameState(MetaState(Wallet(999)), saves = saves)
        state.openSaves()
        assertTrue(state.chooseSave(2))
        assertEquals(MetaState(), state.meta)
        assertEquals(MetaState(), saves.read()[2])
    }

    @Test
    fun `hangar goes back to save selection and slots are re-read`() {
        val saves = MemorySaves()
        val state = GameState(saves = saves)
        state.openSaves()
        state.chooseSave(0)
        saves.write(0, saved)
        assertTrue(state.openSaves())
        assertEquals(saved, state.slots[0])
    }

    @Test
    fun `deleting frees the slot and forgets it if it was active`() {
        val state = GameState(saves = MemorySaves(mapOf(0 to saved)))
        state.openSaves()
        state.chooseSave(0)
        state.openSaves()
        assertFalse(state.deleteSave(1), "empty slot")
        assertTrue(state.deleteSave(0))
        assertNull(state.slots[0])
        assertNull(state.activeSlot)
    }

    @Test
    fun `slot actions only work on the save screen`() {
        val state = GameState(saves = MemorySaves(mapOf(0 to saved)))
        assertFalse(state.chooseSave(0))
        assertFalse(state.deleteSave(0))
        state.openSaves()
        assertFalse(state.chooseSave(7))
    }

    @Test
    fun `game over, victory and quitting a run lead to the hangar`() {
        val state = GameState()
        state.startRun()
        state.gameOver()
        assertTrue(state.openHangar())
        state.openLoadout(ShipType.Rebel)
        state.startRun()
        state.pause()
        assertTrue(state.openHangar())
        assertEquals(ScreenState.HANGAR, state.screen)
    }
}
