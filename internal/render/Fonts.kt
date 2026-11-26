package com.spaceace.game.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator
import com.badlogic.gdx.utils.Disposable
import com.spaceace.game.Config
import kotlin.math.ceil
// --- Размер шрифта ---

/**
 * Размер шрифта (em) в пикселях экрана для текста масштаба [virtualScale].
 * Масштаб 1 — это [baseSize] виртуальных px, как у прежнего встроенного шрифта; [screenScale] — пикселей экрана
 * на виртуальный px. Округляется вверх до шага [step]: шрифт чуть уменьшается при рисовании (чётко) и никогда
 * не растягивается (мыло), а число разных размеров в кэше остаётся небольшим.
 */
fun fontPixelSize(virtualScale: Float, screenScale: Float, baseSize: Float, step: Int, minSize: Int): Int {
    require(virtualScale > 0f && screenScale > 0f && baseSize > 0f && step >= 1) {
        "invalid font sizing: scale=$virtualScale screen=$screenScale base=$baseSize step=$step"
    }
    val exact = baseSize * virtualScale * screenScale
    return (ceil(exact / step).toInt() * step).coerceAtLeast(minSize)
}

/** Множитель для BitmapFont размера [pixelSize], при котором em в виртуальных координатах = [baseSize] × [virtualScale]. */
fun fontDrawScale(virtualScale: Float, pixelSize: Int, baseSize: Float): Float = baseSize * virtualScale / pixelSize

// --- Шрифты ---

/**
 * Шрифты интерфейса, растеризованные FreeType под реальные пиксели экрана: каждый размер генерируется один раз
 * и кэшируется, при изменении размера окна кэш сбрасывается. Нет файла шрифта — встроенный шрифт LibGDX.
 */
class UiFonts : Disposable {
    private val cfg = Config.Assets
    private val generator: FreeTypeFontGenerator? =
        Gdx.files.internal(cfg.FONT).takeIf { it.exists() }?.let(::FreeTypeFontGenerator)
    private val fallback = BitmapFont()
    private val cache = HashMap<Int, BitmapFont>()
    private var screenScale = 1f

    /** [screenScale] — сколько пикселей экрана приходится на виртуальный px (у FitViewport — по ширине). */
    fun resize(screenScale: Float) {
        if (screenScale <= 0f || screenScale == this.screenScale) return
        this.screenScale = screenScale
        clearCache()
    }

    /**
     * Шрифт, готовый рисовать текст масштаба [virtualScale] в виртуальных координатах.
     * Возвращённый объект общий для близких масштабов — использовать сразу, до следующего вызова.
     */
    fun at(virtualScale: Float): BitmapFont {
        val gen = generator ?: return fallback.also { it.data.setScale(virtualScale) }
        val pixels = fontPixelSize(virtualScale, screenScale, cfg.FONT_BASE_SIZE, cfg.FONT_SIZE_STEP, cfg.FONT_MIN_PIXELS)
        val font = cache.getOrPut(pixels) { generate(gen, pixels) }
        font.data.setScale(fontDrawScale(virtualScale, pixels, cfg.FONT_BASE_SIZE))
        return font
    }

    override fun dispose() {
        clearCache()
        generator?.dispose()
        fallback.dispose()
    }

    private fun generate(gen: FreeTypeFontGenerator, pixels: Int): BitmapFont =
        gen.generateFont(
            FreeTypeFontGenerator.FreeTypeFontParameter().apply {
                size = pixels
                minFilter = Texture.TextureFilter.Linear
                magFilter = Texture.TextureFilter.Linear
            },
        )

    private fun clearCache() {
        cache.values.forEach(BitmapFont::dispose)
        cache.clear()
    }
}
