package com.spaceace.game.screen

import com.badlogic.gdx.Application
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.InputMultiplexer
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.utils.viewport.FitViewport
import com.spaceace.game.Config
import com.spaceace.game.input.DesktopInput
import com.spaceace.game.input.InputSource
import com.spaceace.game.input.TouchInput
import com.spaceace.game.logic.GameLogic
import com.spaceace.game.physics.PhysicsSystem
import com.spaceace.game.render.DebugOverlay
import com.spaceace.game.render.DebugStats
import com.spaceace.game.render.EntityRenderer
import com.spaceace.game.render.FieldRenderer
import com.spaceace.game.render.HazardRenderer
import com.spaceace.game.render.Sprites
import com.spaceace.game.render.UiRenderer
import com.spaceace.game.save.AutoSave
import com.spaceace.game.save.PreferencesStore
import com.spaceace.game.save.SaveSlots
import com.spaceace.game.save.SettingsStore
import com.spaceace.game.state.GameState
import com.spaceace.game.state.ScreenState
import com.spaceace.game.ui.UiController
import ktx.app.KtxScreen
import ktx.app.clearScreen
import ktx.assets.disposeSafely
import kotlin.random.Random

// --- Экран игры ---

class GameScreen : KtxScreen {
    private val camera = OrthographicCamera()
    private val viewport = FitViewport(Config.Display.VIRTUAL_WIDTH, Config.Display.VIRTUAL_HEIGHT, camera)

    private val random = Random.Default
    private val saves = SaveSlots { name -> PreferencesStore(Gdx.app.getPreferences(name)) }
    private val settings = SettingsStore(PreferencesStore(Gdx.app.getPreferences(Config.Save.SETTINGS_NAME)))
    private val state = GameState(random = random, saves = saves, settingsLibrary = settings)
    private val autoSave = AutoSave(state, saves)
    private val physics = PhysicsSystem(state)
    private val touch = TouchInput(viewport)
    private val input: InputSource =
        if (Gdx.app.type == Application.ApplicationType.Desktop) DesktopInput(touch) { state.settings.bindings } else touch
    private val ui = UiController(state, viewport)
    private val logic = GameLogic(state, physics, input, random)
    private val stepper = FixedStepper(Config.Physics.STEP_SETTINGS)

    private val sprites = Sprites()
    private val fieldRenderer = FieldRenderer()
    private val entityRenderer = EntityRenderer(sprites)
    private val hazardRenderer = HazardRenderer()
    private val uiRenderer = UiRenderer(sprites, keyHints = Gdx.app.type == Application.ApplicationType.Desktop)
    private val debugOverlay = DebugOverlay()
    private var lastFrameSteps = 0

    override fun show() {
        Gdx.input.inputProcessor = InputMultiplexer(ui, touch)
    }

    override fun render(delta: Float) {
        update(delta)
        draw(delta)
    }

    private fun update(delta: Float) {
        // На экранах-меню UI забирает все касания, и TouchInput не узнает об отпускании пальца.
        if (state.screen != ScreenState.PLAYING) touch.reset()
        val steps = stepper.consume(delta)
        repeat(steps) { logic.step(stepper.step) }
        lastFrameSteps = steps
        autoSave.update()
    }

    private fun draw(delta: Float) {
        clearScreen(Config.Display.LETTERBOX_R, Config.Display.LETTERBOX_G, Config.Display.LETTERBOX_B)
        viewport.apply()
        // На паузе картинки и звёзды замирают вместе с миром; в меню звёзды летят как фон.
        fieldRenderer.render(camera, if (state.screen == ScreenState.PAUSE) 0f else delta)
        if (state.showsField) {
            hazardRenderer.renderZones(camera, state.hazards)
            entityRenderer.render(camera, state.entities.all, if (state.screen == ScreenState.PLAYING) delta else 0f)
            hazardRenderer.renderBeams(camera, state.hazards)
        }
        uiRenderer.render(camera, state, ui.view)
        if (Config.Debug.SHOW_OVERLAY && state.screen == ScreenState.PLAYING) {
            debugOverlay.render(camera, DebugStats(Gdx.graphics.framesPerSecond, lastFrameSteps, state.entities.size))
        }
    }

    override fun resize(width: Int, height: Int) {
        viewport.update(width, height, true)
        // FitViewport сохраняет пропорции, поэтому масштаб по ширине и высоте одинаков.
        uiRenderer.resize(viewport.screenWidth / Config.Display.VIRTUAL_WIDTH)
    }

    /** Сворачивание на Android и закрытие окна на desktop приходят сюда до dispose. */
    override fun pause() {
        state.pause()
        autoSave.flush()
    }

    override fun hide() {
        Gdx.input.inputProcessor = null
    }

    override fun dispose() {
        autoSave.flush()
        physics.disposeSafely()
        fieldRenderer.disposeSafely()
        entityRenderer.disposeSafely()
        hazardRenderer.disposeSafely()
        uiRenderer.disposeSafely()
        debugOverlay.disposeSafely()
        sprites.disposeSafely()
    }
}

// --- Фиксированный шаг ---

data class FixedStepSettings(
    val step: Float,
    val maxFrameTime: Float,
    val maxStepsPerFrame: Int,
) {
    init {
        require(step > 0f) { "step must be positive: $step" }
        require(maxFrameTime >= step) { "maxFrameTime ($maxFrameTime) must be >= step ($step)" }
        require(maxStepsPerFrame >= 1) { "maxStepsPerFrame must be >= 1: $maxStepsPerFrame" }
    }
}

data class StepPlan(val steps: Int, val remainder: Float)

/**
 * Сколько фиксированных шагов выполнить за кадр и сколько времени останется в аккумуляторе.
 *
 * Защита от «спирали смерти»: длинный кадр (лаг, сворачивание окна) обрезается до
 * [FixedStepSettings.maxFrameTime], а если шагов всё равно больше лимита — накопленный
 * долг отбрасывается, и симуляция кратко замедляется вместо зависания.
 */
fun planFixedSteps(accumulator: Float, frameDelta: Float, settings: FixedStepSettings): StepPlan {
    require(frameDelta >= 0f) { "frameDelta must be non-negative: $frameDelta" }
    val total = accumulator + frameDelta.coerceAtMost(settings.maxFrameTime)
    val available = (total / settings.step).toInt()
    val steps = available.coerceAtMost(settings.maxStepsPerFrame)
    val remainder = if (available > steps) total % settings.step else total - steps * settings.step
    return StepPlan(steps, remainder.coerceAtLeast(0f))
}

class FixedStepper(private val settings: FixedStepSettings) {
    private var accumulator = 0f

    val step: Float get() = settings.step

    /** Доля шага, оставшаяся в аккумуляторе, [0; 1) — для интерполяции рендера. */
    val alpha: Float get() = accumulator / settings.step

    /** Добавляет время кадра и возвращает число шагов, которые нужно выполнить сейчас. */
    fun consume(frameDelta: Float): Int {
        val plan = planFixedSteps(accumulator, frameDelta, settings)
        accumulator = plan.remainder
        return plan.steps
    }
}
