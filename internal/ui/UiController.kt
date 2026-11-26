package com.spaceace.game.ui

import com.badlogic.gdx.Input.Keys
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.viewport.Viewport
import com.spaceace.game.Config
import com.spaceace.game.input.Control
import com.spaceace.game.state.GameState
import com.spaceace.game.state.ScreenState

/**
 * Первый процессор в InputMultiplexer: забирает нажатия на кнопки UI. На экранах-меню забирает все касания,
 * в игре остальные касания уходят в TouchInput (управление кораблём и огонь).
 */
class UiController(private val state: GameState, private val viewport: Viewport) : InputAdapter() {
    var view = UiView()
        private set
    private val point = Vector2()

    // Начало возможного свайпа в ангаре: палец, опущенный не на кнопку.
    private var swipePointer: Int? = null
    private var swipeStartX = 0f
    private var swipeStartY = 0f

    private val modal: Boolean get() = state.screen != ScreenState.PLAYING

    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        viewport.unproject(point.set(screenX.toFloat(), screenY.toFloat()))
        val hit = buttonAt(layout(state, view), point.x, point.y)
        if (hit == null) {
            if (state.screen in SWIPE_SCREENS) {
                swipePointer = pointer
                swipeStartX = point.x
                swipeStartY = point.y
            }
            return modal
        }
        perform(hit.action)
        return true
    }

    override fun touchDragged(screenX: Int, screenY: Int, pointer: Int) = modal

    override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        if (pointer == swipePointer) {
            swipePointer = null
            viewport.unproject(point.set(screenX.toFloat(), screenY.toFloat()))
            when (state.screen) {
                // Карточки листаются горизонтально: палец влево — дальше.
                ScreenState.HANGAR -> swipeStep(point.x - swipeStartX).takeIf { it != 0 }?.let { perform(UiAction.ScrollHangar(it)) }
                ScreenState.SAVES -> swipeStep(point.x - swipeStartX).takeIf { it != 0 }?.let { perform(UiAction.ScrollSaves(it)) }
                ScreenState.BESTIARY -> swipeStep(swipeStartY - point.y).takeIf { it != 0 }?.let { perform(UiAction.ScrollBestiary(it)) }
                // Подвесы — вертикальный список: палец вверх — дальше (Y в игре растёт вверх).
                ScreenState.LOADOUT -> swipeStep(swipeStartY - point.y).takeIf { it != 0 }?.let { perform(UiAction.ScrollStores(it)) }
                else -> Unit
            }
        }
        return modal
    }

    override fun keyDown(keycode: Int): Boolean {
        val awaiting = view.awaitingKey
        if (state.screen == ScreenState.SETTINGS && awaiting != null) {
            // Ждём клавишу для действия: Esc отменяет, любая другая назначается.
            perform(if (keycode == Config.Controls.RESERVED_KEY) UiAction.AwaitKey(null) else UiAction.Bind(awaiting, keycode))
            return true
        }
        val action = keyAction(keycode) ?: return false
        perform(action)
        return true
    }

    private fun keyAction(keycode: Int): UiAction? = when (state.screen) {
        ScreenState.PLAYING -> battleKey(keycode, UiAction.Pause)
        ScreenState.PAUSE -> battleKey(keycode, UiAction.Resume)
        ScreenState.SETTINGS -> if (keycode == Keys.ESCAPE) UiAction.CloseSettings else null
        ScreenState.SHOP -> if (keycode == Keys.ENTER) UiAction.LeaveShop else null
        ScreenState.MENU -> if (keycode == Keys.ENTER) UiAction.OpenSaves else null
        ScreenState.SAVES -> when (keycode) {
            Keys.ESCAPE -> UiAction.ToMenu
            Keys.LEFT, Keys.A -> UiAction.ScrollSaves(-1)
            Keys.RIGHT, Keys.D -> UiAction.ScrollSaves(1)
            else -> null
        }
        ScreenState.HANGAR -> when (keycode) {
            Keys.ESCAPE -> UiAction.OpenSaves
            Keys.LEFT, Keys.A -> UiAction.ScrollHangar(-1)
            Keys.RIGHT, Keys.D -> UiAction.ScrollHangar(1)
            else -> null
        }
        ScreenState.LOADOUT -> when (keycode) {
            Keys.ENTER -> UiAction.Play
            Keys.ESCAPE -> UiAction.OpenHangar
            Keys.UP, Keys.W -> UiAction.ScrollStores(-1)
            Keys.DOWN, Keys.S -> UiAction.ScrollStores(1)
            else -> null
        }
        ScreenState.META_SHOP -> if (keycode == Keys.ESCAPE) UiAction.OpenHangar else null
        ScreenState.BESTIARY -> when (keycode) {
            Keys.ESCAPE -> if (view.bestiaryEntry != null) UiAction.CloseEntry else UiAction.OpenHangar
            Keys.UP, Keys.W -> UiAction.ScrollBestiary(-1)
            Keys.DOWN, Keys.S -> UiAction.ScrollBestiary(1)
            else -> null
        }
        ScreenState.GAME_OVER, ScreenState.VICTORY -> when (keycode) {
            Keys.ENTER -> UiAction.Play
            Keys.ESCAPE -> UiAction.OpenHangar
            else -> null
        }
    }

    /** В бою и в паузе: Esc и клавиша паузы переключают паузу, клавиши слотов активируют слоты. */
    private fun battleKey(keycode: Int, pauseAction: UiAction): UiAction? {
        if (keycode == Keys.ESCAPE) return pauseAction
        val control = state.settings.bindings.controlOf(keycode) ?: return null
        if (control == Control.PAUSE) return pauseAction
        return control.slotIndex?.let { UiAction.ActivateSlot(it) }
    }

    private fun perform(action: UiAction) {
        view = state.perform(action, view)
    }

    private companion object {
        val SWIPE_SCREENS = setOf(ScreenState.SAVES, ScreenState.HANGAR, ScreenState.LOADOUT, ScreenState.BESTIARY)
    }
}
