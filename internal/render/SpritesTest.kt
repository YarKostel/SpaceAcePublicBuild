package com.spaceace.game.render

import com.spaceace.game.Config
import com.spaceace.game.content.Levels
import com.spaceace.game.content.Roster
import com.spaceace.game.model.Hitbox
import com.spaceace.game.model.ShipType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpritesTest {
    // Тесты запускаются из папки internal/, ассеты лежат рядом в assets/.
    private fun asset(spec: SpriteSpec) = File("../assets", spec.file)

    @Test
    fun `every ship has a picture on disk`() {
        for (ship in ShipType.ALL) assertTrue(asset(spriteOf(ship)).isFile, "no picture for $ship")
    }

    @Test
    fun `every enemy, boss and ally met in a run has a picture on disk`() {
        val kinds = Levels.ALL.flatMap { level -> level.enemies.map { it.kind } + level.bosses } + Roster.ALLIES
        for (kind in kinds) {
            val spec = assertNotNull(spriteOf(kind), "no picture for ${kind.id}")
            assertTrue(asset(spec).isFile, "missing file ${spec.file}")
        }
    }

    @Test
    fun `meteor picture exists`() {
        assertTrue(asset(Config.Sprites.METEOR).isFile)
    }

    @Test
    fun `picture width follows the hitbox width`() {
        val spec = SpriteSpec("x.png", size = 1.5f)
        assertEquals(1.5f * 0.8f, spriteWidth(spec, Hitbox.Circle(0.4f)), 1e-5f)
    }

    @Test
    fun `meteors spin both ways and never faster than the limit`() {
        val spins = (1..200).map(::meteorSpin)
        assertTrue(spins.all { kotlin.math.abs(it) <= Config.Sprites.METEOR_SPIN })
        assertTrue(spins.any { it > 0f } && spins.any { it < 0f })
    }
}
