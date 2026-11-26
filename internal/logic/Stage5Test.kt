package com.spaceace.game.logic

import com.spaceace.game.model.EntityId
import com.spaceace.game.model.MeteorSize
import com.spaceace.game.model.PowerUpType
import com.spaceace.game.model.Vec2
import com.spaceace.game.model.newMeteor
import com.spaceace.game.model.Field
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Random с постоянным результатом: [bits] = 0 → nextFloat() = 0; иначе — почти 1 (только для nextFloat). */
private class FixedRandom(private val high: Boolean) : Random() {
    override fun nextBits(bitCount: Int): Int = if (high) (1 shl bitCount) - 1 else 0
}

class MeteorSpawnerTest {
    private val field = Field(7.2f, 12.8f)
    private val settings = SpawnerSettings(
        baseInterval = 1f, intervalJitter = 0f, minSpeed = 2f, maxSpeed = 2f, maxDrift = 0f, spawnHeight = 1f,
        difficultyStep = 0.5f, speedStep = 0.5f, sizeWeights = mapOf(MeteorSize.MEDIUM to 1f),
    )
    private val dt = 0.1f

    private fun countSpawns(level: Int, seconds: Float): Int {
        val spawner = MeteorSpawner(settings, field, Random(7))
        return (0 until (seconds / dt).toInt()).sumOf { spawner.update(dt, level).size }
    }

    @Test
    fun `spawns immediately and then once per interval`() {
        assertEquals(10, countSpawns(level = 1, seconds = 9.5f))
    }

    @Test
    fun `higher level spawns more often and faster`() {
        assertEquals(15, countSpawns(level = 2, seconds = 9.5f))
        val spawn = MeteorSpawner(settings, field, Random(7)).update(dt, level = 3).single()
        assertEquals(-4f, spawn.velocity.y, 1e-4f)
    }

    @Test
    fun `meteors appear above the field and inside the walls`() {
        val spawner = MeteorSpawner(settings.copy(intervalJitter = 0.3f, maxDrift = 1f), field, Random(3))
        repeat(500) {
            for (spawn in spawner.update(1f, level = 1)) {
                val hitbox = spawn.size.stats.hitbox
                assertTrue(spawn.position.x - hitbox.halfWidth >= 0f && spawn.position.x + hitbox.halfWidth <= field.width, "$spawn")
                assertTrue(spawn.position.y - hitbox.bottom >= field.height, "$spawn")
                assertTrue(spawn.velocity.y < 0f)
            }
        }
    }

    @Test
    fun `weighted pick respects zero weights`() {
        val random = Random(1)
        repeat(200) { assertEquals("b", weightedPick(mapOf("a" to 0f, "b" to 1f), random)) }
    }

    @Test
    fun `difficulty grows linearly from level one`() {
        assertEquals(1f, difficulty(1, 0.35f))
        assertEquals(1.7f, difficulty(3, 0.35f), 1e-5f)
    }
}

class LootTest {
    private val settings = LootSettings(
        fragments = mapOf(MeteorSize.LARGE to MeteorSize.SMALL),
        fragmentCount = 2..3,
        fragmentSpeed = 1f,
        fragmentOffset = 0.5f,
        moneyChance = 0.5f,
        money = mapOf(MeteorSize.SMALL to 1..2, MeteorSize.LARGE to 4..7),
        powerUpChance = 0.1f,
    )
    private val pool = listOf(PowerUpType.Shield)

    @Test
    fun `large meteor splits into small fragments that keep parent velocity`() {
        val parent = newMeteor(EntityId(1), MeteorSize.LARGE, Vec2(3f, 6f), Vec2(0f, -2f))
        val fragments = fragmentsOf(parent, settings, FixedRandom(high = false))
        assertEquals(2, fragments.size)
        assertTrue(fragments.all { it.size == MeteorSize.SMALL })
        val meanVelocity = fragments.map { it.velocity }.reduce(Vec2::plus) * (1f / fragments.size)
        assertEquals(-2f, meanVelocity.y, 1e-4f)
        assertEquals(0f, meanVelocity.x, 1e-4f)
    }

    @Test
    fun `fragment count stays in range`() {
        val parent = newMeteor(EntityId(1), MeteorSize.LARGE, Vec2.ZERO, Vec2.ZERO)
        val random = Random(11)
        repeat(100) { assertTrue(fragmentsOf(parent, settings, random).size in 2..3) }
    }

    @Test
    fun `small meteor does not split`() {
        val parent = newMeteor(EntityId(1), MeteorSize.SMALL, Vec2.ZERO, Vec2.ZERO)
        assertTrue(fragmentsOf(parent, settings, Random(1)).isEmpty())
    }

    @Test
    fun `lucky roll gives money and power-up`() {
        val loot = meteorLoot(MeteorSize.LARGE, settings, pool, FixedRandom(high = false))
        assertEquals(4, loot.money)
        assertEquals(PowerUpType.Shield, loot.powerUp)
    }

    @Test
    fun `unlucky roll gives nothing`() {
        val loot = meteorLoot(MeteorSize.LARGE, settings, pool, FixedRandom(high = true))
        assertEquals(0, loot.money)
        assertNull(loot.powerUp)
    }
}
