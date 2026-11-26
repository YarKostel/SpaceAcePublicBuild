package com.spaceace.game.logic

import com.badlogic.gdx.physics.box2d.Box2D
import com.spaceace.game.Config
import com.spaceace.game.content.Shard
import com.spaceace.game.input.ControlState
import com.spaceace.game.input.steeringImpulse
import com.spaceace.game.input.targetVelocityX
import com.spaceace.game.input.targetVelocityY
import com.spaceace.game.model.Player
import com.spaceace.game.model.Vec2
import com.spaceace.game.physics.PhysicsSystem
import com.spaceace.game.state.GameState
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Движение корабля на настоящем Box2D без метеоритов и врагов: та же рулёжка, что в GameLogic,
 * но без спауна, чтобы удары не мешали проверять границы.
 */
class PlayerMovementTest {
    private val dt = Config.Physics.TIME_STEP
    private val margin = 0.05f

    @BeforeTest
    fun loadBox2D() = Box2D.init()

    /** Держит [control] [seconds] секунд; возвращает путь корабля (позиции после каждого шага). */
    private fun fly(control: ControlState, seconds: Float): List<Vec2> {
        val state = GameState()
        state.startRun()
        val physics = PhysicsSystem(state)
        val path = ArrayList<Vec2>()
        try {
            repeat((seconds / dt).toInt()) {
                val player = assertNotNull(state.player)
                val target = Vec2(
                    targetVelocityX(player.position.x, control, Config.Player.STEERING),
                    targetVelocityY(player.position.y, control, Config.Player.STEERING, Config.Player.CLIMB),
                )
                val accel = Config.Player.STEERING.maxAccel
                physics.applyImpulse(
                    player.id,
                    Vec2(
                        steeringImpulse(player.mass, player.velocity.x, target.x, accel, dt),
                        steeringImpulse(player.mass, player.velocity.y, target.y, accel, dt),
                    ),
                )
                physics.step(dt)
                path += assertNotNull(state.player).position
            }
        } finally {
            physics.dispose()
        }
        return path
    }

    private val player: Player get() = GameState().also { it.startRun() }.player!!

    @Test
    fun `holding up climbs but never reaches the boss`() {
        val path = fly(ControlState(axisY = 1f), seconds = 15f)
        val ceiling = Config.Player.CLIMB.ceilingY
        assertTrue(path.last().y > Config.Player.START_Y + 2f, "ship did not climb: ${path.last()}")
        assertTrue(path.all { it.y <= ceiling + margin }, "flew above the ceiling: ${path.maxOf { it.y }}")
        assertTrue(path.maxOf { it.y } + player.hitbox.top < Shard.HOVER_Y, "reached the boss height")
    }

    @Test
    fun `climbing slows down with height`() {
        val path = fly(ControlState(axisY = 1f), seconds = 6f)
        val stepsPerSecond = (1f / dt).toInt()
        val early = path[stepsPerSecond].y - path[0].y
        val late = path[5 * stepsPerSecond].y - path[4 * stepsPerSecond].y
        assertTrue(late < early / 2, "climbed ${early}m in the first second but ${late}m in the fifth")
    }

    @Test
    fun `holding down stops at the floor above the slots`() {
        val path = fly(ControlState(axisY = -1f), seconds = 5f)
        val lowest = path.minOf { it.y } - player.hitbox.bottom
        assertTrue(lowest >= Config.Player.FLOOR_Y - margin, "ship dipped to $lowest below the floor ${Config.Player.FLOOR_Y}")
    }

    @Test
    fun `diagonal input moves on both axes`() {
        val start = player.position
        val end = fly(ControlState(axis = 1f, axisY = 1f), seconds = 0.5f).last()
        assertTrue(end.x > start.x + 1f && end.y > start.y + 1f, "from $start to $end")
    }

    @Test
    fun `finger target is followed in two dimensions`() {
        val target = Vec2(1.5f, 5f)
        val end = fly(ControlState(targetX = target.x, targetY = target.y), seconds = 4f).last()
        assertTrue((end - target).length < 0.2f, "ship stopped at $end instead of $target")
    }
}
