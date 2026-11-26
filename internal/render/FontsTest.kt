package com.spaceace.game.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FontSizingTest {
    private val base = 15f
    private val eps = 1e-4f

    @Test
    fun `size follows text scale and window size`() {
        // Окно 562×1000: 0.78 px экрана на виртуальный px; заголовок ×4 → 15·4·0.78 = 46.8 → 48 при шаге 2.
        assertEquals(48, fontPixelSize(4f, 0.78f, base, step = 2, minSize = 8))
        assertEquals(30, fontPixelSize(2f, 1f, base, step = 2, minSize = 8))
        assertEquals(60, fontPixelSize(2f, 2f, base, step = 2, minSize = 8), "bigger window, bigger glyphs")
    }

    @Test
    fun `size is rounded up so text is never stretched`() {
        for (scale in listOf(0.9f, 1f, 1.2f, 1.5f, 2f, 4f)) for (screen in listOf(0.5f, 0.625f, 0.78f, 1f, 1.5f)) {
            val pixels = fontPixelSize(scale, screen, base, step = 2, minSize = 8)
            assertTrue(pixels >= base * scale * screen - eps, "scale $scale screen $screen → $pixels")
            assertEquals(0, pixels % 2)
        }
    }

    @Test
    fun `tiny text still gets a readable minimum`() {
        assertEquals(8, fontPixelSize(0.1f, 0.5f, base, step = 2, minSize = 8))
    }

    @Test
    fun `draw scale gives the same size in virtual units as before`() {
        // Встроенный шрифт 15 px с setScale(2) давал em 30 виртуальных px — новый должен давать столько же.
        for (screen in listOf(0.625f, 0.78f, 1.3f)) {
            val pixels = fontPixelSize(2f, screen, base, step = 2, minSize = 8)
            assertEquals(30f, pixels * fontDrawScale(2f, pixels, base), eps)
        }
    }

    @Test
    fun `nonsense input is rejected`() {
        assertFailsWith<IllegalArgumentException> { fontPixelSize(0f, 1f, base, 2, 8) }
        assertFailsWith<IllegalArgumentException> { fontPixelSize(1f, 1f, base, 0, 8) }
    }
}
