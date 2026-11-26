package com.spaceace.game.content

import com.spaceace.game.Config
import com.spaceace.game.model.Hitbox
import com.spaceace.game.model.MeteorSize
import com.spaceace.game.model.NpcBody
import com.spaceace.game.model.NpcKind
import com.spaceace.game.model.NpcLoot
import com.spaceace.game.model.ProjectileKind
import com.spaceace.game.model.Role
import com.spaceace.game.model.degrees
import com.spaceace.game.npc.Aim
import com.spaceace.game.npc.AttackPlan
import com.spaceace.game.npc.AttackResult
import com.spaceace.game.npc.AttackStep
import com.spaceace.game.npc.Behavior
import com.spaceace.game.npc.BehaviorPhase
import com.spaceace.game.npc.Burst
import com.spaceace.game.npc.Environment
import com.spaceace.game.npc.Memory
import com.spaceace.game.npc.Movement
import com.spaceace.game.npc.NpcInput
import com.spaceace.game.npc.SideMeteorSettings
import com.spaceace.game.npc.Transition
import com.spaceace.game.npc.aimedShot
import kotlin.math.abs

/**
 * Босс первого этапа. Движение и корпус — как у прежнего Gunship: спуск, затем патруль по X у верхнего края.
 * Фаза 1: очереди по 10 пуль в игрока со средним разбросом; после каждых 2–5 таких очередей — очередь из трёх
 * зенитных снарядов (по 8 осколков). Фаза 2 (HP < 50%): из-за боковых краёв летят метеориты; если игрок держится
 * под боссом (проекции хитбоксов на X пересекаются) 2.5 с — гаусс-выстрел в игрока, пробивающий метеориты.
 *
 * Черновик из прежнего Config.Boss, если пригодится для следующего босса:
 * PR1177 — Hitbox.Rect(3f, 3f), density 3, HP 1000, armor 0.8, патруль 2.2 м/с, награда 60.
 */
object Shard : NpcKind {
    override val id = "shard"
    override val title = "SHARD-CLASS LIGHT PATROL CORVETTE"
    override val description = listOf(
        "Light corvette guarding the outer lanes.",
        "Sprays long bursts and flak. When damaged, it drags meteors into the fight and snipes anyone lingering below it.",
    )
    override val role = Role.BOSS

    /** Высота патруля: у верхнего края, ниже неё игрок подняться не может (потолок корабля). */
    val HOVER_Y = Config.Physics.WORLD_HEIGHT - 2.2f
    private const val RAGE_HEALTH = 0.5f
    private const val ALIGN_TIME = 2.5f
    private const val GAUSS_SPEED = 22f

    override val body = NpcBody(
        hitbox = Hitbox.Rect(1.9f, 0.9f),
        density = 3f,
        restitution = 0f,
        maxHealth = 450f,
        armor = 0.8f,
        physical = false,
        maxAccel = 10f,
    )

    private val patrol = Movement.Patrol(hoverY = HOVER_Y, speed = 2.2f, entrySpeed = 2f, edgeMargin = 0.2f, hoverGain = 3f)

    private val bursts = AttackPlan.Cycle(
        steps = listOf(
            AttackStep(
                Burst(ProjectileKind.BOSS_BOLT, count = 10, interval = 0.09f, speed = 8f, aim = Aim.AtTarget(), spread = degrees(8f)),
                repeats = 2..5,
                pause = 1f,
            ),
            AttackStep(
                Burst(ProjectileKind.ENEMY_FLAK_SHELL, count = 3, interval = 0.3f, speed = 6f, aim = Aim.AtTarget()),
                pause = 1.5f,
            ),
        ),
        startDelay = 1.6f,
    )

    private val sideMeteors = SideMeteorSettings(
        interval = 0.45f,
        intervalJitter = 0.3f,
        minSpeed = 3.5f,
        maxSpeed = 5.5f,
        minAngle = degrees(15f),
        maxAngle = degrees(40f),
        minY = 0.45f,
        maxY = 0.9f,
        sizeWeights = mapOf(MeteorSize.SMALL to 0.5f, MeteorSize.MEDIUM to 0.3f, MeteorSize.LARGE to 0.2f),
    )

    override val behavior = Behavior(
        listOf(
            BehaviorPhase(movement = patrol, attacks = bursts, exit = Transition.HealthBelow(RAGE_HEALTH)),
            BehaviorPhase(movement = patrol, attacks = AttackPlan.Custom(::snipeWhenBelow), environment = Environment.SideMeteors(sideMeteors)),
        ),
    )

    override val loot = NpcLoot(money = 60..60, trophy = true)

    /** Сколько секунд подряд игрок под боссом. */
    internal data class AlignMemory(val aligned: Float = 0f) : Memory

    internal fun snipeWhenBelow(input: NpcInput): AttackResult {
        val npc = input.npc
        val state = npc.brain.attack
        val player = input.sight.target ?: return AttackResult(emptyList(), state.copy(memory = AlignMemory()))
        val overlap = abs(player.position.x - npc.position.x) < npc.hitbox.halfWidth + player.hitbox.halfWidth
        val aligned = if (overlap) ((state.memory as? AlignMemory)?.aligned ?: 0f) + input.dt else 0f
        if (aligned < ALIGN_TIME) return AttackResult(emptyList(), state.copy(memory = AlignMemory(aligned)))
        val shot = aimedShot(npc, ProjectileKind.ENEMY_GAUSS_SLUG, player.position - npc.position, GAUSS_SPEED)
        return AttackResult(listOf(shot), state.copy(memory = AlignMemory()))
    }
}
