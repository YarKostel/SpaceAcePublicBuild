package com.spaceace.game.android

import android.os.Bundle
import com.badlogic.gdx.backends.android.AndroidApplication
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration
import com.spaceace.game.Config
import com.spaceace.game.Main

class AndroidLauncher : AndroidApplication() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = AndroidApplicationConfiguration().apply {
            useImmersiveMode = true
            useAccelerometer = false
            useCompass = false
            // Сглаживание краёв фигур; на телефоне меньше сэмплов, чем на ПК, — дешевле для GPU.
            numSamples = Config.Display.MSAA_SAMPLES_ANDROID
        }
        initialize(Main(), config)
    }
}
