package com.spaceace.game.weapon

import com.badlogic.gdx.physics.box2d.Box2D
import com.spaceace.game.Config
import com.spaceace.game.model.MeteorSize
import com.spaceace.game.model.Projectile
import com.spaceace.game.model.ProjectileKind
import com.spaceace.game.model.Vec2
import com.spaceace.game.model.newMeteor
import com.spaceace.game.model.newProjectile
import com.spaceace.game.physics.PhysicsSystem
import com.spaceace.game.state.GameState
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Поведение снарядов в полёте на настоящем Box2D: разрыв Flak и самонаведение ракет. */
class ProjectileSystemTest {
    private val dt = Config.Physics.TIME_STEP

    @BeforeTest
    fun loadBox2D() = Box2D.init()

    private fun simulate(seconds: Float, setup: (GameState) -> Unit, check: (GameState, Int) -> Unit) {
        val state = GameState()
        state.startRun()
        setup(state)
        val physics = PhysicsSystem(state)
        val projectiles = ProjectileSystem(state, physics, Random(1))
        try {
            repeat((seconds / dt).toInt()) { step ->
                projectiles.step(dt)
                physics.step(dt)
                check(state, step)
            }
        } finally {
            physics.dispose()
        }
    }

    private fun GameState.projectiles(kind: ProjectileKind) = entities.ofType<Projectile>().filter { it.kind == kind }

    @Test
    fun `flak shell turns into shrapnel after the fuse`() {
        val fuseSteps = (Config.Weapons.Flak.FUSE / dt).toInt()
        var shrapnelSeen = 0
        simulate(
            seconds = Config.Weapons.Flak.FUSE + 0.3f,
            setup = { it.entities.spawn { id -> newProjectile(id, ProjectileKind.FLAK_SHELL, Vec2(3.6f, 4f), Vec2(0f, Config.Weapons.Flak.SPEED)) } },
            check = { state, step ->
                if (step < fuseSteps - 1) assertEquals(1, state.projectiles(ProjectileKind.FLAK_SHELL).size, "early explosion at $step")
                shrapnelSeen = maxOf(shrapnelSeen, state.projectiles(ProjectileKind.FLAK_SHRAPNEL).size)
            },
        )
        assertEquals(Config.Weapons.Flak.SHRAPNEL_COUNT, shrapnelSeen)
    }

    @Test
    fun `missile turns towards a meteor off to the side`() {
        var lastVelocity = Vec2.ZERO
        simulate(
            seconds = 0.4f,
            setup = { state ->
                state.entities.spawn { newMeteor(it, MeteorSize.LARGE, Vec2(6f, 8f), Vec2.ZERO) }
                state.entities.spawn { newProjectile(it, ProjectileKind.MISSILE, Vec2(1f, 3f), Vec2(0f, 13f)) }
            },
            check = { state, _ -> state.projectiles(ProjectileKind.MISSILE).firstOrNull()?.let { lastVelocity = it.velocity } },
        )
        assertTrue(lastVelocity.x > 1f, "missile should veer right towards the meteor, velocity $lastVelocity")
    }

    @Test
    fun `configured rounds of different sides fly through each other`() {
        var met = false
        var left = 0
        simulate(
            seconds = 0.5f,
            setup = { state ->
                state.entities.spawn { newProjectile(it, ProjectileKind.GATLING_ROUND, Vec2(1f, 6f), Vec2(0f, 9f)) }
                state.entities.spawn { newProjectile(it, ProjectileKind.ENEMY_BOLT, Vec2(1f, 8f), Vec2(0f, -6f)) }
            },
            check = { state, _ ->
                val round = state.projectiles(ProjectileKind.GATLING_ROUND).singleOrNull()
                val bolt = state.projectiles(ProjectileKind.ENEMY_BOLT).singleOrNull()
                if (round != null && bolt != null && round.position.y > bolt.position.y) met = true
                left = state.projectiles(ProjectileKind.GATLING_ROUND).size + state.projectiles(ProjectileKind.ENEMY_BOLT).size
            },
        )
        assertTrue(met && left == 2, "both passed each other untouched: met=$met, left=$left")
    }
}
