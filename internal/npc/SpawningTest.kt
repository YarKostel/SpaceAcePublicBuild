package com.spaceace.game.npc

import com.spaceace.game.content.Borer
import com.spaceace.game.content.Gravel
import com.spaceace.game.model.EntityId
import com.spaceace.game.model.Entry
import com.spaceace.game.model.Field
import com.spaceace.game.model.MeteorSize
import com.spaceace.game.model.Npc
import com.spaceace.game.model.Vec2
import com.spaceace.game.model.degrees
import com.spaceace.game.model.newMeteor
import com.spaceace.game.model.newNpc
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NpcSpawnerTest {
    private val field = Field(7.2f, 12.8f)
    private val settings = NpcSpawnerSettings(
        baseInterval = 5f, intervalJitter = 0f, difficultyStep = 0f, spawnHeight = 1f, groupSpacing = 1.1f, ambushMargin = 0.3f,
    )
    private val entries = listOf(SpawnEntry(Gravel, weight = 1f, group = 1..2), SpawnEntry(Borer, weight = 1f, group = 2..3))

    @Test
    fun `first group comes after a full interval, then one per interval`() {
        val spawner = NpcSpawner(settings, field, Random(1))
        val steps = (1..1210).filter { spawner.update(1f / 60f, 1, entries).isNotEmpty() }
        assertEquals(4, steps.size, "$steps")
        steps.forEachIndexed { i, step -> assertTrue(abs(step - 300 * (i + 1)) <= 1, "$steps") }
    }

    @Test
    fun `groups have the configured size, one kind, fit the field and start above it`() {
        val spawner = NpcSpawner(settings.copy(intervalJitter = 0.3f), field, Random(5))
        val sizes = HashMap<String, MutableSet<Int>>()
        repeat(300) {
            val group = spawner.update(5f, 1, entries)
            if (group.isEmpty()) return@repeat
            val kind = group.first().kind
            assertTrue(group.all { it.kind == kind })
            sizes.getOrPut(kind.id) { HashSet() } += group.size
            for (spawn in group) {
                val hitbox = spawn.kind.body.hitbox
                assertTrue(spawn.position.x - hitbox.halfWidth >= -1e-4f && spawn.position.x + hitbox.halfWidth <= field.width + 1e-4f, "$spawn")
                assertTrue(spawn.position.y - hitbox.bottom >= field.height - 1e-4f, "$spawn")
            }
        }
        assertEquals<Set<Int>?>(setOf(1, 2), sizes["gravel"])
        assertEquals<Set<Int>?>(setOf(2, 3), sizes["borer"])
    }

    @Test
    fun `formation shapes`() {
        val row = formationOffsets(Shape.ROW, 3, 1f)
        assertTrue(row.all { it.y == 0f } && row.map { it.x } == listOf(0f, 1f, 2f))
        val column = formationOffsets(Shape.COLUMN, 3, 1f)
        assertTrue(column.all { it.x == 0f } && column.map { it.y } == listOf(0f, 1f, 2f))
        val wedge = formationOffsets(Shape.WEDGE, 5, 1f)
        assertEquals(Vec2.ZERO, wedge[0], "leader in front")
        assertTrue(wedge.drop(1).all { it.y > 0f }, "wings behind (above) the leader: $wedge")
        assertEquals(-wedge[1].x, wedge[2].x, 1e-4f)
        assertEquals(wedge[1].y, wedge[2].y, 1e-4f)
        assertTrue(wedge[3].y > wedge[1].y && kotlin.math.abs(wedge[3].x) > kotlin.math.abs(wedge[1].x))
    }

    @Test
    fun `formation group fits the field and starts above it`() {
        val spawner = NpcSpawner(settings, field, Random(4))
        repeat(50) {
            val wedge = spawner.update(5f, 1, listOf(SpawnEntry(Borer, 1f, 3..3, SpawnPattern.Formation(Shape.WEDGE))))
            if (wedge.isEmpty()) return@repeat
            assertEquals(3, wedge.size)
            val half = Borer.body.hitbox.halfWidth
            assertTrue(wedge.all { it.position.x >= half - 1e-4f && it.position.x <= field.width - half + 1e-4f }, "$wedge")
            assertTrue(wedge.all { it.position.y >= field.height }, "$wedge")
        }
    }

    @Test
    fun `wave comes one by one from the same point`() {
        val spawner = NpcSpawner(settings, field, Random(2))
        val wave = listOf(SpawnEntry(Gravel, 1f, 3..3, SpawnPattern.Wave(interval = 0.5f)))
        val arrivals = ArrayList<Pair<Int, Vec2>>()
        for (step in 1..400) spawner.update(1f / 60f, 1, wave).forEach { arrivals += step to it.position }
        assertEquals(3, arrivals.size, "$arrivals")
        assertTrue(arrivals.map { it.second }.toSet().size == 1, "same point: $arrivals")
        val gaps = arrivals.zipWithNext { a, b -> b.first - a.first }
        assertTrue(gaps.all { abs(it - 30) <= 1 }, "$gaps")
    }

    @Test
    fun `reset drops the rest of an unfinished wave`() {
        val spawner = NpcSpawner(settings, field, Random(2))
        val wave = listOf(SpawnEntry(Gravel, 1f, 3..3, SpawnPattern.Wave(interval = 0.5f)))
        assertEquals(1, spawner.update(5f, 1, wave).size)
        spawner.reset()
        assertTrue(spawner.update(1f, 1, wave).isEmpty())
    }

    @Test
    fun `ambush starts behind one side edge and enters to a point inside`() {
        val spawner = NpcSpawner(settings, field, Random(8))
        val ambush = SpawnPattern.Ambush(speed = 3f, minDepth = 0.2f, maxDepth = 0.5f, minY = 0.55f, maxY = 0.85f)
        var sides = 0
        repeat(60) {
            val group = spawner.update(5f, 1, listOf(SpawnEntry(Borer, 1f, 2..3, ambush)))
            if (group.isEmpty()) return@repeat
            val fromLeft = group.first().position.x < 0f
            sides = sides or if (fromLeft) 1 else 2
            val half = Borer.body.hitbox.halfWidth
            for ((i, spawn) in group.withIndex()) {
                val entry = checkNotNull(spawn.entry)
                assertEquals(3f, entry.speed)
                assertEquals(spawn.position.y, entry.target.y, "flies in horizontally")
                if (fromLeft) assertTrue(spawn.position.x + half < 0f) else assertTrue(spawn.position.x - half > field.width)
                val depth = if (fromLeft) entry.target.x else field.width - entry.target.x
                assertTrue(depth in field.width * 0.2f - 1e-3f..field.width * 0.5f + 1e-3f, "depth $depth")
                if (i > 0) assertTrue(spawn.position.y < group[i - 1].position.y, "stacked one under another")
            }
            assertTrue(group.first().position.y in field.height * 0.55f..field.height * 0.85f)
        }
        assertEquals(3, sides, "both sides are used")
    }

    @Test
    fun `entering npc flies to its point and then switches to its own AI`() {
        val start = newNpc(EntityId(1), Gravel, Vec2(-0.5f, 8f), entry = Entry(Vec2(2f, 8f), speed = 3f))
        val velocity = checkNotNull(entryVelocity(start))
        assertEquals(Vec2(3f, 0f), velocity)
        assertEquals(null, entryVelocity(start.moved(Vec2(1.9f, 8f), Vec2.ZERO) as Npc))
    }

    @Test
    fun `nothing spawns without entries`() {
        val spawner = NpcSpawner(settings, field, Random(1))
        assertTrue(spawner.update(10f, 1, emptyList()).isEmpty())
    }
}

class SideMeteorTest {
    private val field = Field(7.2f, 12.8f)
    private val settings = SideMeteorSettings(
        interval = 0.5f, intervalJitter = 0f, minSpeed = 3f, maxSpeed = 5f, minAngle = degrees(15f), maxAngle = degrees(40f),
        minY = 0.5f, maxY = 0.9f, sizeWeights = mapOf(MeteorSize.SMALL to 1f, MeteorSize.LARGE to 1f),
    )

    @Test
    fun `meteors appear behind the side edges and fly inwards and down`() {
        val spawner = SideMeteorSpawner(field, Random(2))
        var left = 0
        var right = 0
        repeat(200) {
            for (spawn in spawner.update(0.5f, settings)) {
                val half = spawn.size.stats.hitbox.halfWidth
                assertTrue(spawn.velocity.y < 0f)
                assertTrue(spawn.position.y in field.height * 0.5f..field.height * 0.9f)
                if (spawn.position.x < 0f) {
                    left++
                    assertEquals(-half, spawn.position.x, 1e-4f)
                    assertTrue(spawn.velocity.x > 0f)
                } else {
                    right++
                    assertEquals(field.width + half, spawn.position.x, 1e-4f)
                    assertTrue(spawn.velocity.x < 0f)
                }
                val slope = -spawn.velocity.y / abs(spawn.velocity.x)
                assertTrue(slope in 0.26f..0.84f, "angle 15..40 degrees: $slope")
            }
        }
        assertTrue(left > 0 && right > 0)
    }

    @Test
    fun `meteor has entered once it is whole inside the field`() {
        val outside = newMeteor(EntityId(1), MeteorSize.SMALL, Vec2(0f, 6f), Vec2.ZERO, entering = true)
        assertFalse(hasEntered(outside, field))
        assertTrue(hasEntered(outside.moved(Vec2(1f, 6f), Vec2.ZERO) as com.spaceace.game.model.Meteor, field))
    }
}
