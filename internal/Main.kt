package com.spaceace.game

import com.badlogic.gdx.physics.box2d.Box2D
import com.spaceace.game.screen.GameScreen
import ktx.app.KtxGame
import ktx.app.KtxScreen

class Main : KtxGame<KtxScreen>(clearScreen = false) {
    override fun create() {
        Box2D.init()
        addScreen(GameScreen())
        setScreen<GameScreen>()
    }
}
