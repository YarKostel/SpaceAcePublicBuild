package com.spaceace.game.state

import com.spaceace.game.Config
import com.spaceace.game.model.BASE_DROPS
import com.spaceace.game.model.Perk
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InventoryTest {
    private val ricochet = PowerUpType.WeaponPickup(WeaponType.RICOCHET)
    private val shieldGen = PowerUpType.PerkPickup(Perk.SHIELD_GENERATOR)
    private fun loadout() = PowerUpInventory.loadout(WeaponType.MACHINE_GUN, PowerUpType.PerkPickup(Perk.HEAVY_ROUNDS_SUPPLY))

    @Test
    fun `loadout puts base weapon first and store second`() {
        val inventory = loadout()
        assertEquals(Config.Inventory.SLOTS, inventory.slots.size)
        assertEquals(Slot.Armament(WeaponType.MACHINE_GUN), inventory.slots[0])
        assertEquals(Slot.Gear(Perk.HEAVY_ROUNDS_SUPPLY), inventory.slots[1])
        assertTrue(inventory.slots.drop(2).all { it == null })
    }

    @Test
    fun `supplies stack, weapons and perks take one slot each`() {
        var inventory = loadout().add(PowerUpType.Shield)!!.add(PowerUpType.Shield)!!
        assertEquals(Slot.Supply(PowerUpType.Shield, 2), inventory.slots[2])
        inventory = inventory.add(ricochet)!!
        assertEquals(Slot.Armament(WeaponType.RICOCHET), inventory.slots[3])
    }

    @Test
    fun `picking up a weapon or perk you already have changes nothing`() {
        val inventory = loadout()
        assertSame(inventory, inventory.add(PowerUpType.WeaponPickup(WeaponType.MACHINE_GUN)))
        assertSame(inventory, inventory.add(PowerUpType.PerkPickup(Perk.HEAVY_ROUNDS_SUPPLY)))
    }

    @Test
    fun `full inventory refuses new things but still stacks supplies`() {
        val full = loadout().add(PowerUpType.Shield)!!.add(ricochet)!!.add(shieldGen)!!
        assertNull(full.add(PowerUpType.Repair))
        assertNotNull(full.add(PowerUpType.Shield))
    }

    @Test
    fun `using a weapon slot equips it and keeps it`() {
        val inventory = loadout().add(ricochet)!!
        val (after, use) = inventory.use(2)!!
        assertEquals(SlotUse.Equip(WeaponType.RICOCHET), use)
        assertEquals(inventory, after)
    }

    @Test
    fun `using a perk starts its effect and cooldown, then it is unavailable until ready`() {
        val (after, use) = loadout().use(1)!!
        val perk = Perk.HEAVY_ROUNDS_SUPPLY
        assertEquals(SlotUse.Effect(perk.effect!!, perk.duration), use)
        assertEquals(perk.cooldown, assertIs<Slot.Gear>(after.slots[1]).cooldown)
        assertNull(after.use(1))
        val ready = after.tick(perk.cooldown)
        assertTrue(assertIs<Slot.Gear>(ready.slots[1]).ready)
        assertNotNull(ready.use(1))
    }

    @Test
    fun `using a supply spends one`() {
        val inventory = loadout().add(PowerUpType.Repair)!!.add(PowerUpType.Repair)!!
        val (once, use) = inventory.use(2)!!
        assertEquals(SlotUse.Effect(PowerUpType.Repair, PowerUpType.Repair.duration), use)
        assertEquals(1, (once.slots[2] as Slot.Supply).count)
        assertNull(once.use(2)!!.first.slots[2])
        assertNull(once.use(4))
        assertNull(once.use(99))
    }

    @Test
    fun `only the ship weapon slot cannot be cleared`() {
        val inventory = loadout().add(ricochet)!!
        assertNull(inventory.clear(0))
        assertNull(inventory.clear(1)!!.slots[1], "the store slot can be cleared")
        assertNull(inventory.clear(2)!!.slots[2])
        assertNull(inventory.clear(3))
    }

    @Test
    fun `tick does not allocate when nothing cools down`() {
        val inventory = loadout()
        assertSame(inventory, inventory.tick(1f))
    }
}

class ActiveEffectsTest {
    @Test
    fun `activation starts the effect and repeated activation extends it`() {
        val shield = PowerUpType.Shield
        val once = ActiveEffects().activate(shield)
        assertEquals(shield.duration, once.timeLeft(shield))
        assertEquals(shield.duration * 2, once.activate(shield).timeLeft(shield))
        assertEquals(shield.duration * 2 + 3f, once.activate(shield).activate(shield, 3f).timeLeft(shield))
    }

    @Test
    fun `effect expires after its duration`() {
        val effects = ActiveEffects().activate(PowerUpType.HeavyRounds)
        assertTrue(effects.tick(PowerUpType.HeavyRounds.duration - 0.1f).isActive(PowerUpType.HeavyRounds))
        assertFalse(effects.tick(PowerUpType.HeavyRounds.duration).isActive(PowerUpType.HeavyRounds))
    }
}

class ShopTest {
    @Test
    fun `shop offers distinct power-ups with prices`() {
        repeat(50) { seed ->
            val shop = Shop.generate(BASE_DROPS, 3, Random(seed))
            assertEquals(3, shop.offers.size)
            assertEquals(3, shop.offers.map { it.type }.toSet().size)
            assertTrue(shop.offers.all { it.price == priceOf(it.type) && !it.sold })
        }
    }

    private fun stateInShop(money: Int): GameState {
        val state = GameState(MetaState(Wallet(money)), Random(1))
        state.startRun()
        state.advancePhase()
        state.advancePhase()
        return state
    }

    @Test
    fun `entering the shop phase creates offers`() {
        val state = stateInShop(0)
        assertEquals(ScreenState.SHOP, state.screen)
        assertEquals(Config.Shop.OFFERS, state.run.shop?.offers?.size)
    }

    @Test
    fun `buying spends money, fills a slot and sells the offer once`() {
        val state = stateInShop(1000)
        val offer = state.run.shop!!.offers[0]
        assertTrue(state.buy(0))
        assertEquals(1000 - offer.price, state.meta.wallet.balance)
        assertEquals(offer.type, state.run.inventory.slots[Config.Inventory.FIXED_SLOTS]?.type)
        assertFalse(state.buy(0))
        assertEquals(1000 - offer.price, state.meta.wallet.balance)
    }

    @Test
    fun `cannot buy without money`() {
        val state = stateInShop(0)
        assertFalse(state.buy(0))
        assertTrue(state.run.inventory.slots.drop(Config.Inventory.FIXED_SLOTS).all { it == null })
    }

    @Test
    fun `leaving the shop drops the offers`() {
        val state = stateInShop(0)
        state.advancePhase()
        assertNull(state.run.shop)
        assertFalse(state.buy(0))
    }
}

class PowerUpUseTest {
    private fun playing(ship: ShipType = ShipType.Rebel): GameState =
        GameState(MetaState(selectedShip = ship)).also { it.startRun() }

    @Test
    fun `run starts with the ship weapon equipped and the starter store`() {
        val glory = playing(ShipType.Glory)
        assertEquals(WeaponType.GATLING, glory.run.weapon)
        assertEquals(Slot.Armament(WeaponType.IR_MISSILES), glory.run.inventory.slots[1])
        val rebel = playing(ShipType.Rebel)
        assertEquals(WeaponType.MACHINE_GUN, rebel.run.weapon)
        assertEquals(Slot.Gear(Perk.HEAVY_ROUNDS_SUPPLY), rebel.run.inventory.slots[1])
    }

    @Test
    fun `weapon slots switch the weapon back and forth and stay`() {
        val state = playing(ShipType.Glory)
        assertTrue(state.activateSlot(1))
        assertEquals(WeaponType.IR_MISSILES, state.run.weapon)
        assertTrue(state.activateSlot(0))
        assertEquals(WeaponType.GATLING, state.run.weapon)
        assertEquals(Slot.Armament(WeaponType.IR_MISSILES), state.run.inventory.slots[1])
    }

    @Test
    fun `perk gives a short effect and goes on cooldown`() {
        val state = playing(ShipType.Rebel)
        assertTrue(state.activateSlot(1))
        assertEquals(Perk.HEAVY_ROUNDS_SUPPLY.duration, state.run.effects.timeLeft(PowerUpType.HeavyRounds))
        assertFalse(state.activateSlot(1))
        state.tick(Perk.HEAVY_ROUNDS_SUPPLY.cooldown)
        assertTrue(state.activateSlot(1))
    }

    @Test
    fun `timed supplies stack and extend the effect`() {
        val state = playing()
        state.pickUp(PowerUpType.Shield)
        state.pickUp(PowerUpType.Shield)
        assertTrue(state.activateSlot(2))
        assertTrue(state.activateSlot(2))
        assertEquals(PowerUpType.Shield.duration * 2, state.run.effects.timeLeft(PowerUpType.Shield))
        assertFalse(state.activateSlot(2))
    }

    @Test
    fun `only the free slots take pickups`() {
        val state = playing()
        val free = Config.Inventory.SLOTS - Config.Inventory.FIXED_SLOTS
        val distinct = listOf(PowerUpType.Shield, PowerUpType.Repair, PowerUpType.HeavyRounds)
        repeat(free) { assertTrue(state.pickUp(distinct[it])) }
        assertFalse(state.pickUp(PowerUpType.WeaponPickup(WeaponType.GAUSS)))
    }

    @Test
    fun `slots work in pause but not in menu`() {
        val state = GameState()
        assertFalse(state.activateSlot(0))
        state.startRun()
        state.pickUp(PowerUpType.Shield)
        state.pause()
        assertTrue(state.clearSlot(2))
        assertFalse(state.clearSlot(0))
    }

    @Test
    fun `clearing the current weapon falls back to the ship weapon`() {
        val state = GameState(MetaState(selectedShip = ShipType.Glory))
        state.startRun()
        val store = (state.run.inventory.slots[1] as Slot.Armament).weapon
        assertTrue(state.activateSlot(1))
        assertEquals(store, state.run.weapon)
        assertTrue(state.clearSlot(1))
        assertNull(state.run.inventory.slots[1])
        assertEquals(ShipType.Glory.stats.baseWeapon, state.run.weapon)
    }
}
