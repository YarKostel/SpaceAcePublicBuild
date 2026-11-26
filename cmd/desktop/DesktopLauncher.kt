package com.spaceace.game.desktop

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.spaceace.game.Config
import com.spaceace.game.Main

private object DesktopConfig {
    const val TITLE = "SpaceAce"
    /** Доля высоты монитора под окно: остаётся место для панели задач и заголовка окна. */
    const val SCREEN_HEIGHT_FRACTION = 0.85f
    // Буферы цвета/глубины/трафарета по умолчанию LWJGL3; меняется только число сэмплов MSAA.
    const val COLOR_BITS = 8
    const val DEPTH_BITS = 16
    const val STENCIL_BITS = 0
}

fun main() {
    val display = Lwjgl3ApplicationConfiguration.getDisplayMode()
    // Окно по высоте монитора с пропорциями 720×1280: больше пикселей на корабли и надписи.
    val height = (display.height * DesktopConfig.SCREEN_HEIGHT_FRACTION).toInt()
    val width = (height * Config.Display.VIRTUAL_WIDTH / Config.Display.VIRTUAL_HEIGHT).toInt()
    val config = Lwjgl3ApplicationConfiguration().apply {
        setTitle(DesktopConfig.TITLE)
        setWindowedMode(width, height)
        setBackBufferConfig(
            DesktopConfig.COLOR_BITS, DesktopConfig.COLOR_BITS, DesktopConfig.COLOR_BITS, DesktopConfig.COLOR_BITS,
            DesktopConfig.DEPTH_BITS, DesktopConfig.STENCIL_BITS, Config.Display.MSAA_SAMPLES_DESKTOP,
        )
        setResizable(true)
        useVsync(true)
        // Лимит 60 поверх vsync 144 Гц давал рваные 12–30 FPS: кадры ждали то лимитер, то vsync.
        // Ограничиваем частотой монитора; логика всё равно идёт фиксированным шагом 60 Гц.
        setForegroundFPS(display.refreshRate.coerceAtLeast(Config.Display.TARGET_FPS))
    }
    Lwjgl3Application(Main(), config)
}
