package com.spaceace.game.ui

import com.spaceace.game.input.Control
import com.spaceace.game.model.ALL_POWER_UPS
import com.spaceace.game.model.PowerUpType
import com.spaceace.game.model.ShipType
import com.spaceace.game.model.WeaponType
import com.spaceace.game.state.Counter
import com.spaceace.game.state.GameState
import com.spaceace.game.state.MemorySaves
import com.spaceace.game.state.MetaState
import com.spaceace.game.state.ScreenState
import com.spaceace.game.state.Wallet
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiTest {
    private fun center(button: UiButton) = button.rect.centerX to button.rect.centerY

    private fun tap(state: GameState, view: UiView, action: UiAction): UiView {
        val button = assertNotNull(layout(state, view).firstOrNull { it.action == action }, "no button for $action")
        val (x, y) = center(button)
        val hit = assertNotNull(buttonAt(layout(state, view), x, y))
        return state.perform(hit.action, view)
    }

    @Test
    fun `full loop through the screens with taps`() {
        val state = GameState(random = Random(3), saves = MemorySaves(mapOf(0 to MetaState(Wallet(500)))))
        var view = UiView()
        view = tap(state, view, UiAction.OpenSaves)
        assertEquals(ScreenState.SAVES, state.screen)
        view = tap(state, view, UiAction.ChooseSave(0))
        assertEquals(ScreenState.HANGAR, state.screen)
        view = tap(state, view, UiAction.OpenMetaShop)
        view = tap(state, view, UiAction.BuyGood(Counter.ITEMS, 0))
        assertTrue(state.meta.owns(Counter.ITEMS, 0))
        view = tap(state, view, UiAction.OpenHangar)
        view = tap(state, view, UiAction.BuyShip(ShipType.Glory))
        view = tap(state, view, UiAction.SelectShip(ShipType.Glory))
        assertEquals(ScreenState.LOADOUT, state.screen)
        view = tap(state, view, UiAction.PickStore(1))
        view = tap(state, view, UiAction.Play)
        assertEquals(ScreenState.PLAYING, state.screen)
        assertEquals(ShipType.Glory, state.player?.ship)
        view = tap(state, view, UiAction.Pause)
        assertEquals(ScreenState.PAUSE, state.screen)
        view = tap(state, view, UiAction.ToggleStorage)
        assertTrue(view.showStorage)
        view = tap(state, view, UiAction.Resume)
        assertFalse(view.showStorage)
        state.advancePhase()
        state.advancePhase()
        assertEquals(ScreenState.SHOP, state.screen)
        view = tap(state, view, UiAction.Buy(0))
        assertTrue(state.run.shop!!.offers[0].sold)
        tap(state, view, UiAction.LeaveShop)
        assertEquals(ScreenState.PLAYING, state.screen)
    }

    @Test
    fun `disabled buttons cannot be hit`() {
        val state = GameState()
        state.startRun()
        val emptySlot = layout(state, UiView()).first { it.action == UiAction.ActivateSlot(4) }
        assertFalse(emptySlot.enabled)
        assertNull(buttonAt(listOf(emptySlot), emptySlot.rect.centerX, emptySlot.rect.centerY))
    }

    @Test
    fun `slot button shows type and count`() {
        val state = GameState()
        state.startRun()
        state.pickUp(PowerUpType.Shield)
        state.pickUp(PowerUpType.Shield)
        val slot = layout(state, UiView()).first { it.action == UiAction.ActivateSlot(2) }
        assertEquals("SHLD x2", slot.label)
        assertTrue(slot.enabled)
    }

    @Test
    fun `shop offers are disabled when too expensive`() {
        val state = GameState(MetaState(Wallet(0)), Random(1))
        state.startRun()
        state.advancePhase()
        state.advancePhase()
        val offers = layout(state, UiView()).filter { it.action is UiAction.Buy }
        assertEquals(3, offers.size)
        assertTrue(offers.none { it.enabled })
    }

    @Test
    fun `buttons on one screen do not overlap`() {
        val state = GameState(MetaState(Wallet(1000)), Random(1))
        val screens = mutableListOf<List<UiButton>>()
        screens += layout(state, UiView())
        val saves = GameState(saves = MemorySaves((0..3).associateWith { MetaState() }))
        saves.openSaves()
        screens += layout(saves, UiView(savesFirst = 1, confirmDelete = 2))
        state.openHangar()
        screens += layout(state, UiView())
        state.openMetaShop()
        screens += layout(state, UiView())
        state.openHangar()
        state.openLoadout(ShipType.Rebel)
        screens += layout(state, UiView())
        state.startRun()
        listOf(PowerUpType.Shield, PowerUpType.WeaponPickup(WeaponType.GAUSS), PowerUpType.Repair).forEach(state::pickUp)
        screens += layout(state, UiView())
        state.pause()
        screens += layout(state, UiView(showStorage = true))
        state.openSettings()
        screens += layout(state, UiView(awaitingKey = Control.FIRE))
        state.closeSettings()
        state.resume()
        state.advancePhase()
        state.advancePhase()
        screens += layout(state, UiView())
        for (buttons in screens) {
            for (i in buttons.indices) for (j in i + 1 until buttons.size) {
                val a = buttons[i].rect
                val b = buttons[j].rect
                val overlap = a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height
                assertFalse(overlap, "${buttons[i].label} overlaps ${buttons[j].label}")
            }
        }
    }

    @Test
    fun `save slots scroll and delete needs confirmation`() {
        val saves = MemorySaves((0..2).associateWith { MetaState(Wallet(it * 10)) })
        val state = GameState(saves = saves)
        var view = tap(state, UiView(), UiAction.OpenSaves)
        assertEquals(4, state.slots.size, "all three taken: one extra empty slot")
        assertEquals(listOf("LOAD", "LOAD"), layout(state, view).filter { it.action is UiAction.ChooseSave }.map { it.label })
        view = tap(state, view, UiAction.ScrollSaves(1))
        view = tap(state, view, UiAction.ScrollSaves(1))
        assertEquals("NEW GAME", layout(state, view).first { it.action == UiAction.ChooseSave(3) }.label)

        view = tap(state, view, UiAction.DeleteSave(2))
        assertEquals(2, view.confirmDelete)
        assertEquals("CONFIRM?", layout(state, view).first { it.action == UiAction.DeleteSave(2) }.label)
        assertEquals(MetaState(Wallet(20)), saves.read()[2], "first tap only asks")
        view = tap(state, view, UiAction.DeleteSave(2))
        assertNull(saves.read()[2])
        assertNull(view.confirmDelete)
        assertEquals(3, state.slots.size, "a free slot exists again, the extra one is gone")
    }

    @Test
    fun `scrolling cancels a pending delete`() {
        val state = GameState(saves = MemorySaves((0..2).associateWith { MetaState() }))
        var view = tap(state, UiView(), UiAction.OpenSaves)
        view = tap(state, view, UiAction.DeleteSave(0))
        view = tap(state, view, UiAction.ScrollSaves(1))
        assertNull(view.confirmDelete)
    }

    @Test
    fun `after game over, victory and quit the player lands in the hangar`() {
        val state = GameState()
        state.startRun()
        state.gameOver()
        var view = tap(state, UiView(), UiAction.OpenHangar)
        assertEquals(ScreenState.HANGAR, state.screen)
        state.openLoadout(ShipType.Rebel)
        state.startRun()
        view = tap(state, view, UiAction.Pause)
        tap(state, view, UiAction.OpenHangar)
        assertEquals(ScreenState.HANGAR, state.screen)
        assertEquals(UiAction.OpenSaves, layout(state, UiView()).first { it.label == "BACK" }.action)
    }

    @Test
    fun `hud slots do not cover the fire button`() {
        for (i in 0 until 4) {
            val rect = hudSlotRect(i)
            listOf(rect.x to rect.y, rect.x + rect.width to rect.y + rect.height, rect.x + rect.width to rect.y).forEach { (x, y) ->
                assertFalse(com.spaceace.game.input.isOnFireButton(x, y), "slot $i corner ($x, $y)")
            }
        }
    }

    @Test
    fun `every power-up has labels`() {
        for (type in ALL_POWER_UPS) {
            assertTrue(type.shortLabel().isNotBlank() && type.longLabel().isNotBlank())
        }
    }
}
