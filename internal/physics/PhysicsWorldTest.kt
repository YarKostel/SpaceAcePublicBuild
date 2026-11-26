package com.spaceace.game.physics

import com.badlogic.gdx.physics.box2d.Box2D
import com.spaceace.game.Config
import com.spaceace.game.content.Borer
import com.spaceace.game.content.Gravel
import com.spaceace.game.content.Shard
import com.spaceace.game.model.EntityId
import com.spaceace.game.model.Entry
import com.spaceace.game.model.Meteor
import com.spaceace.game.model.MeteorSize
import com.spaceace.game.model.Npc
import com.spaceace.game.model.Player
import com.spaceace.game.model.Projectile
import com.spaceace.game.model.ProjectileKind
import com.spaceace.game.model.Vec2
import com.spaceace.game.model.newMeteor
import com.spaceace.game.model.newNpc
import com.spaceace.game.model.newProjectile
import com.spaceace.game.npc.NpcSystem
import com.spaceace.game.state.GameState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Столкновения на настоящем Box2D: метеорит против босса, заряд врага против игрока. */
class PhysicsBehaviourTest {
    private val dt = Config.Physics.TIME_STEP

    @BeforeTest
    fun loadBox2D() = Box2D.init()

    private fun withWorld(state: GameState, body: (PhysicsSystem) -> Unit) {
        val physics = PhysicsSystem(state)
        try {
            body(physics)
        } finally {
            physics.dispose()
        }
    }

    @Test
    fun `meteor bounces off the boss and the boss stays put`() {
        val state = GameState()
        val start = Vec2(3.6f, 10f)
        val boss = state.entities.spawn { newNpc(it, Shard, start) }
        val meteor = state.entities.spawn { newMeteor(it, MeteorSize.LARGE, Vec2(3.6f, 11.8f), Vec2(0f, -4f)) }
        withWorld(state) { physics ->
            var bounced = false
            repeat(60) {
                physics.step(dt)
                val m = state.entities[meteor.id] as? Meteor ?: return@repeat
                if (m.velocity.y > 0f) bounced = true
            }
            assertTrue(bounced, "meteor must bounce off the boss instead of passing through")
            val after = assertNotNull(state.entities[boss.id] as? Npc)
            assertEquals(start.x, after.position.x, 1e-3f)
            assertEquals(start.y, after.position.y, 1e-3f)
        }
    }

    @Test
    fun `entering meteor passes the side wall, then the wall holds it`() {
        val state = GameState()
        val start = newMeteor(EntityId(1), MeteorSize.SMALL, Vec2(-0.3f, 8f), Vec2(4f, 0f), entering = true)
        val meteor = state.entities.spawn { start.copy(id = it) }
        withWorld(state) { physics ->
            repeat(30) { physics.step(dt) }
            val inside = assertNotNull(state.entities[meteor.id] as? Meteor)
            assertTrue(inside.position.x > inside.hitbox.halfWidth, "passed the wall: ${inside.position}")
            // Целиком на поле — снова обычный метеорит; летит обратно к стене и отскакивает.
            state.entities.replace(inside.entered().moved(inside.position, Vec2(-4f, 0f)))
            physics.setVelocity(meteor.id, Vec2(-4f, 0f))
            var minX = Float.MAX_VALUE
            repeat(60) {
                physics.step(dt)
                (state.entities[meteor.id] as? Meteor)?.let { minX = minOf(minX, it.position.x) }
            }
            assertTrue(minX > 0f, "bounced off the wall instead of leaving: $minX")
        }
    }

    @Test
    fun `ambusher flies in through the side wall, arrives and stays inside`() {
        val state = GameState()
        val gravel = state.entities.spawn {
            newNpc(it, Gravel, Vec2(-0.7f, 9f), entry = Entry(Vec2(2f, 9f), speed = 3f))
        }
        withWorld(state) { physics ->
            val system = NpcSystem(state, physics, Random(1))
            var arrivedAt = -1
            repeat(180) { step ->
                system.step(dt)
                physics.step(dt)
                val npc = state.entities[gravel.id] as? Npc ?: return@repeat
                if (arrivedAt < 0 && npc.entry == null) arrivedAt = step
            }
            assertTrue(arrivedAt in 1..120, "arrived at step $arrivedAt")
            val inside = assertNotNull(state.entities[gravel.id] as? Npc)
            val half = inside.hitbox.halfWidth
            assertTrue(inside.position.x - half >= -1e-2f, "inside the field: ${inside.position}")
        }
    }

    @Test
    fun `rotating npc body turns and the snapshot follows`() {
        val state = GameState()
        val borer = state.entities.spawn { newNpc(it, Borer, Vec2(3.6f, 8f)) }
        val target = (PI * 0.75).toFloat()
        withWorld(state) { physics ->
            physics.step(dt) // тело создаётся на первом шаге
            physics.turnTo(borer.id, target, dt)
            physics.step(dt)
            val turned = assertNotNull(state.entities[borer.id] as? Npc)
            assertEquals(target, turned.angle, 1e-3f)
        }
    }

    @Test
    fun `enemy gauss pierces a meteor and pushes it`() {
        val state = GameState()
        val meteor = state.entities.spawn { newMeteor(it, MeteorSize.MEDIUM, Vec2(3.6f, 6f), Vec2.ZERO) }
        val slug = state.entities.spawn { newProjectile(it, ProjectileKind.ENEMY_GAUSS_SLUG, Vec2(3.6f, 8f), Vec2(0f, -22f)) }
        withWorld(state) { physics ->
            var slugSeenBelow = false
            repeat(20) {
                physics.step(dt)
                (state.entities[slug.id] as? Projectile)?.let { if (it.position.y < 5f) slugSeenBelow = true }
            }
            assertTrue(slugSeenBelow, "slug must fly through the meteor")
            val pushed = state.entities[meteor.id] as? Meteor
            assertTrue(pushed == null || pushed.velocity.y < -0.1f, "meteor pushed down or destroyed: $pushed")
        }
    }

    @Test
    fun `enemy bolt hurts the player without pushing the ship`() {
        val state = GameState()
        state.startRun()
        val player = assertNotNull(state.player)
        val bolt = state.entities.spawn {
            newProjectile(it, ProjectileKind.ENEMY_BOLT, player.position + Vec2(0f, 1f), Vec2(0f, -8f))
        }
        assertFalse(ProjectileKind.ENEMY_BOLT.stats.pushesPlayer)
        withWorld(state) { physics ->
            repeat(60) { physics.step(dt) }
            val hit = assertNotNull(state.entities.all.firstOrNull { it is Player } as? Player)
            assertTrue(hit.health.current < player.health.current, "the bolt must still deal damage")
            assertTrue(abs(hit.velocity.y) < 1e-3f && abs(hit.velocity.x) < 1e-3f, "ship must not move: ${hit.velocity}")
            assertTrue(state.entities[bolt.id] !is Projectile, "the bolt is gone after the hit")
        }
    }
}
