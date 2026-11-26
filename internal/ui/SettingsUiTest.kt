package com.spaceace.game.ui

import com.badlogic.gdx.Input.Keys
import com.badlogic.gdx.utils.viewport.FitViewport
import com.spaceace.game.Config
import com.spaceace.game.input.Control
import com.spaceace.game.input.KeyBindings
import com.spaceace.game.state.GameState
import com.spaceace.game.state.MemorySettings
import com.spaceace.game.state.ScreenState
import com.spaceace.game.state.Settings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsUiTest {
    private fun tap(state: GameState, view: UiView, action: UiAction): UiView {
        val buttons = layout(state, view)
        val button = assertNotNull(buttons.firstOrNull { it.action == action }, "no button for $action on ${state.screen}")
        val hit = assertNotNull(buttonAt(buttons, button.rect.centerX, button.rect.centerY))
        return state.perform(hit.action, view)
    }

    private fun keyButton(state: GameState, view: UiView, control: Control) =
        layout(state, view).first { it.action == UiAction.AwaitKey(control) }

    @Test
    fun `gear button sits in the top right corner of menu, hangar and pause`() {
        val state = GameState()
        val screens = mutableListOf(layout(state, UiView()))
        state.openHangar()
        screens += layout(state, UiView())
        state.startRun()
        state.pause()
        screens += layout(state, UiView())
        for (buttons in screens) {
            val gear = buttons.single { it.action == UiAction.OpenSettings }
            assertEquals(settingsButtonRect(), gear.rect)
            assertEquals(UiIcon.GEAR, gear.icon)
        }
    }

    @Test
    fun `settings return to the screen they were opened from`() {
        val state = GameState()
        var view = tap(state, UiView(), UiAction.OpenSettings)
        assertEquals(ScreenState.SETTINGS, state.screen)
        view = tap(state, view, UiAction.CloseSettings)
        assertEquals(ScreenState.MENU, state.screen)

        state.openHangar()
        view = tap(state, view, UiAction.OpenSettings)
        tap(state, view, UiAction.CloseSettings)
        assertEquals(ScreenState.HANGAR, state.screen)
    }

    @Test
    fun `settings from pause keep the run paused`() {
        val state = GameState()
        state.startRun()
        state.pause()
        val phase = state.run.phase
        var view = tap(state, UiView(), UiAction.OpenSettings)
        assertFalse(state.resume(), "no resume from settings")
        view = tap(state, view, UiAction.CloseSettings)
        assertEquals(ScreenState.PAUSE, state.screen)
        assertEquals(phase, state.run.phase)
        assertTrue(state.player != null)
    }

    @Test
    fun `settings cannot be opened mid-battle or from the shop`() {
        val state = GameState()
        state.startRun()
        assertFalse(state.openSettings())
        assertTrue(layout(state, UiView()).none { it.action == UiAction.OpenSettings })
    }

    @Test
    fun `controls tab lists every control with its key`() {
        val state = GameState()
        state.openSettings()
        val view = UiView()
        assertEquals(listOf("CONTROLS"), layout(state, view).filter { it.action is UiAction.OpenSettingsTab }.map { it.label })
        assertEquals("S", keyButton(state, view, Control.FIRE).label)
        assertEquals("LEFT", keyButton(state, view, Control.MOVE_LEFT).label)
        assertEquals(Control.entries.size, layout(state, view).count { it.action is UiAction.AwaitKey })
    }

    @Test
    fun `tap a key button, press a key - the key is bound and saved`() {
        val library = MemorySettings()
        val state = GameState(settingsLibrary = library)
        state.openSettings()
        var view = tap(state, UiView(), UiAction.AwaitKey(Control.FIRE))
        assertEquals(Control.FIRE, view.awaitingKey)
        assertEquals("PRESS KEY", keyButton(state, view, Control.FIRE).label)
        assertTrue(keyButton(state, view, Control.FIRE).selected)

        view = state.perform(UiAction.Bind(Control.FIRE, Keys.A), view)
        assertNull(view.awaitingKey)
        assertEquals(Keys.A, state.settings.bindings.keyOf(Control.FIRE))
        assertEquals("-", keyButton(state, view, Control.SLOT_1).label, "slot 1 lost its key")
        assertEquals(state.settings, library.read())
    }

    @Test
    fun `escape is never bound`() {
        val state = GameState()
        state.openSettings()
        assertFalse(state.rebind(Control.FIRE, Config.Controls.RESERVED_KEY))
        assertEquals(KeyBindings.DEFAULT, state.settings.bindings)
    }

    @Test
    fun `tapping the same key button again or anything else stops waiting`() {
        val state = GameState()
        state.openSettings()
        val waiting = state.perform(UiAction.AwaitKey(Control.PAUSE), UiView())
        assertNull(state.perform(UiAction.AwaitKey(Control.PAUSE), waiting).awaitingKey)
        assertEquals(Control.FIRE, state.perform(UiAction.AwaitKey(Control.FIRE), waiting).awaitingKey)
        assertNull(state.perform(UiAction.AwaitKey(null), waiting).awaitingKey)
        assertNull(state.perform(UiAction.ResetControls, waiting).awaitingKey)
    }

    @Test
    fun `defaults button is off for the default layout and restores it`() {
        val state = GameState()
        state.openSettings()
        assertFalse(layout(state, UiView()).single { it.action == UiAction.ResetControls }.enabled)
        state.rebind(Control.FIRE, Keys.SPACE)
        tap(state, UiView(), UiAction.ResetControls)
        assertEquals(KeyBindings.DEFAULT, state.settings.bindings)
    }

    @Test
    fun `settings are shared by every save slot`() {
        val library = MemorySettings(Settings(KeyBindings.DEFAULT.bind(Control.FIRE, Keys.SPACE)))
        val state = GameState(settingsLibrary = library)
        assertEquals(Keys.SPACE, state.settings.bindings.keyOf(Control.FIRE))
        state.openSaves()
        state.chooseSave(1)
        assertEquals(Keys.SPACE, state.settings.bindings.keyOf(Control.FIRE))
    }

    @Test
    fun `settings rows and buttons stay on screen`() {
        val state = GameState()
        state.openSettings()
        for (b in layout(state, UiView())) {
            val r = b.rect
            assertTrue(r.x >= 0f && r.y >= 0f && r.x + r.width <= Config.Display.VIRTUAL_WIDTH && r.y + r.height <= Config.Display.VIRTUAL_HEIGHT, "${b.label} off screen")
        }
        val lowestRow = settingsRowY(Control.entries.lastIndex)
        assertTrue(lowestRow > Config.Ui.SETTINGS_HINT_Y, "hint is below the last row")
        assertTrue(Config.Ui.SETTINGS_HINT_Y > bottomLeftRect().y + bottomLeftRect().height, "hint is above the bottom buttons")
    }

    private fun controller(state: GameState) =
        UiController(state, FitViewport(Config.Display.VIRTUAL_WIDTH, Config.Display.VIRTUAL_HEIGHT))

    @Test
    fun `keys follow the layout in battle and pause`() {
        val state = GameState()
        state.startRun()
        val ui = controller(state)
        assertTrue(ui.keyDown(Keys.P))
        assertEquals(ScreenState.PAUSE, state.screen)
        assertTrue(ui.keyDown(Keys.ESCAPE))
        assertEquals(ScreenState.PLAYING, state.screen)
        assertFalse(ui.keyDown(Keys.NUM_1), "number keys are not slots any more")
        assertTrue(ui.keyDown(Keys.A), "A activates slot 1")
        assertFalse(ui.keyDown(Keys.S), "fire is polled by the input source, not handled by UI")
    }

    @Test
    fun `escape closes settings when nothing is awaited`() {
        val state = GameState()
        state.startRun()
        state.pause()
        state.openSettings()
        assertTrue(controller(state).keyDown(Keys.ESCAPE))
        assertEquals(ScreenState.PAUSE, state.screen)
    }

    @Test
    fun `rebound pause key works and the old one does not`() {
        val state = GameState()
        state.openSettings()
        state.rebind(Control.PAUSE, Keys.Q)
        state.closeSettings()
        state.startRun()
        val ui = controller(state)
        assertFalse(ui.keyDown(Keys.P))
        assertTrue(ui.keyDown(Keys.Q))
        assertEquals(ScreenState.PAUSE, state.screen)
    }
}

/** Подписи клавиш в углах слотов: просьба показать, на какую клавишу забинжен слот. */
class SlotKeyHintTest {
    @Test
    fun `default slot keys are A D Z X C`() {
        val bindings = KeyBindings.DEFAULT
        assertEquals(listOf("A", "D", "Z", "X", "C"), (0 until Config.Inventory.SLOTS).map { slotKeyHint(bindings, it) })
    }

    @Test
    fun `hint follows rebinding and is empty for an unbound slot`() {
        val bindings = KeyBindings.DEFAULT.bind(Control.SLOT_1, Keys.NUM_1).bind(Control.SLOT_2, Keys.NUM_1)
        assertEquals("", slotKeyHint(bindings, 0), "key moved to slot 2, slot 1 is unbound")
        assertEquals("1", slotKeyHint(bindings, 1))
        assertEquals("", slotKeyHint(bindings, Config.Inventory.SLOTS))
    }
}
