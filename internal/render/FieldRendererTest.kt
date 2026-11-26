package com.spaceace.game.render

import com.badlogic.gdx.graphics.Color
import com.spaceace.game.Config
import com.spaceace.game.content.Shard
import com.spaceace.game.model.ALL_POWER_UPS
import com.spaceace.game.model.PermanentItem
import com.spaceace.game.model.PowerUpType
import com.spaceace.game.state.Counter
import com.spaceace.game.state.MetaState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Регрессия вылета: выпавший «вызов дрона» не находил цвета при отрисовке. */
class PowerUpColorTest {
    @Test
    fun `every power-up the game can show has a color`() {
        val everything = MetaState(
            items = MetaState.catalog(Counter.ITEMS).indices.toSet(),
            storeGoods = MetaState.catalog(Counter.STORES).indices.toSet(),
        )
        val types = ALL_POWER_UPS + everything.dropPool + PowerUpType.ItemPickup(PermanentItem.Trophy(Shard))
        for (type in types) powerUpColor(type)
    }

    @Test
    fun `dropped drone call is drawn in its own color`() {
        assertEquals(Color(Config.Render.DRONE_CALL.toInt()), powerUpColor(PowerUpType.DroneCall))
    }
}

class StarFieldTest {
    private val layer = StarLayer(count = 10, speed = 100f, size = 2f, brightness = 0.5f)

    @Test
    fun `same seed gives the same sky`() {
        val layers = listOf(layer, layer.copy(count = 5))
        assertEquals(starsOf(layers, 720f, 1280f, seed = 7), starsOf(layers, 720f, 1280f, seed = 7))
        assertEquals(15, starsOf(layers, 720f, 1280f, seed = 7).size)
    }

    @Test
    fun `stars stay inside the field`() {
        for (star in starsOf(listOf(layer), 720f, 1280f, seed = 1)) {
            assertTrue(star.x in 0f..720f && star.y in 0f..1280f)
        }
    }

    @Test
    fun `star moves down and wraps to the top`() {
        val star = Star(x = 0f, y = 50f, layer = 0)
        assertEquals(40f, starY(star, layer, time = 0.1f, height = 1280f), 1e-3f)
        assertEquals(1230f, starY(star, layer, time = 1f, height = 1280f), 1e-3f)
    }

    @Test
    fun `configured near layers are faster and brighter than far ones`() {
        Config.Stars.LAYERS.zipWithNext().forEach { (far, near) ->
            assertTrue(near.speed > far.speed && near.brightness > far.brightness)
        }
    }
}
