package com.spaceace.game.content

import com.spaceace.game.model.HitZone
import com.spaceace.game.model.Hitbox
import com.spaceace.game.model.NpcBody
import com.spaceace.game.model.NpcKind
import com.spaceace.game.model.NpcLoot
import com.spaceace.game.model.Role
import com.spaceace.game.model.Vec2
import com.spaceace.game.model.degrees
import com.spaceace.game.model.normalizeAngle
import com.spaceace.game.model.noseAngle
import com.spaceace.game.model.noseDirection
import com.spaceace.game.npc.Behavior
import com.spaceace.game.npc.BehaviorPhase
import com.spaceace.game.npc.MoveResult
import com.spaceace.game.npc.Movement
import com.spaceace.game.npc.NpcInput
import kotlin.math.PI

/**
 * Таранщик. Нос (±30°) наносит ×1.5 урона, фронт (±60°) получает урон вдвое меньше. Нос поворачивается только
 * в нижней полусфере. Игрок ниже — разворачивается к нему носом и таранит. Игрок выше: в нижней четверти поля —
 * уходит вниз и исчезает; иначе поднимается, пока снова не сможет навести нос, и заходит на новый таран.
 * Появляется группами по 2–3 (см. Levels).
 */
object Borer : NpcKind {
    override val id = "borer"
    override val title = "MK-12 SPACE BORER"
    override val description = listOf(
        "Mining drone refitted as a ram.",
        "Its drill nose hits harder, and the frontal armour halves incoming damage. Strike it from the sides.",
    )
    override val role = Role.MOB

    private const val RAM_SPEED = 3f
    private const val CLIMB_SPEED = 2f
    private const val LEAVE_SPEED = 4f
    private val TURN_RATE = degrees(150f)
    /** Ниже этой доли высоты поля Borer, упустивший игрока, уходит вниз, а не набирает высоту. */
    private const val EXIT_ZONE = 0.33f

    private val DOWN = PI.toFloat()
    /** Нижняя полусфера: нос смотрит от горизонтали влево (π/2) через низ (π) до горизонтали вправо (3π/2). */
    private val LOWER_FROM = (PI / 2).toFloat()
    private val LOWER_TO = (3 * PI / 2).toFloat()

    override val body = NpcBody(
        hitbox = Hitbox.Triangle(base = 0.55f, height = 1f),
        density = 1.5f,
        restitution = 0.2f,
        maxHealth = 16f,
        armor = 1f,
        physical = true,
        maxAccel = 9f,
        rotates = true,
        startAngle = DOWN,
        zones = listOf(
            HitZone(halfAngle = degrees(30f), dealtScale = 1.5f),
            HitZone(halfAngle = degrees(60f), takenScale = 0.5f),
        ),
    )

    override val behavior = Behavior(BehaviorPhase(movement = Movement.Custom(::steer)))

    override val loot = NpcLoot(money = 4..8, powerUpChance = 0.15f)

    internal fun steer(input: NpcInput): MoveResult {
        val npc = input.npc
        val state = npc.brain.move
        val current = lower(npc.angle)
        val maxTurn = TURN_RATE * input.dt
        val player = input.sight.target
        val toPlayer = player?.let { lower(noseAngle(it.position - npc.position)) }
        return when {
            player == null -> {
                val angle = turn(current, DOWN, maxTurn)
                MoveResult(noseDirection(angle) * RAM_SPEED, state, angle)
            }
            player.position.y < npc.position.y -> {
                val angle = turn(current, toPlayer!!, maxTurn)
                MoveResult(noseDirection(angle) * RAM_SPEED, state, angle)
            }
            npc.position.y < input.sight.field.height * EXIT_ZONE ->
                MoveResult(Vec2(0f, -LEAVE_SPEED), state, turn(current, DOWN, maxTurn))
            else -> MoveResult(Vec2(0f, CLIMB_SPEED), state, turn(current, toPlayer!!, maxTurn))
        }
    }

    /** Угол, прижатый к нижней полусфере (в [π/2; 3π/2]). */
    internal fun lower(angle: Float): Float {
        val a = normalizeAngle(angle)
        return when {
            a in LOWER_FROM..LOWER_TO -> a
            a < LOWER_FROM -> LOWER_FROM
            else -> LOWER_TO
        }
    }

    /** Поворот внутри нижней полусферы не больше чем на [maxTurn]: через низ, а не через верх. */
    private fun turn(current: Float, target: Float, maxTurn: Float): Float =
        current + (target - current).coerceIn(-maxTurn, maxTurn)
}
