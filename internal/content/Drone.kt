package com.spaceace.game.content

import com.spaceace.game.model.Hitbox
import com.spaceace.game.model.NpcBody
import com.spaceace.game.model.NpcKind
import com.spaceace.game.model.NpcLoot
import com.spaceace.game.model.Perk
import com.spaceace.game.model.PowerUpType
import com.spaceace.game.model.ProjectileKind
import com.spaceace.game.model.Role
import com.spaceace.game.model.SummonSpec
import com.spaceace.game.npc.Aim
import com.spaceace.game.npc.AttackPlan
import com.spaceace.game.npc.AttackStep
import com.spaceace.game.npc.Behavior
import com.spaceace.game.npc.BehaviorPhase
import com.spaceace.game.npc.Burst
import com.spaceace.game.npc.Movement
import kotlin.math.PI

// --- Дрон сопровождения ---

/**
 * Союзный дрон сопровождения: держится рядом с кораблём (своё место на кольце), стреляет короткими очередями
 * по ближайшему врагу, а без врагов — по ближайшему метеориту; корпусом закрывает от вражеских снарядов.
 */
object Drone : NpcKind {
    override val id = "wasp-drone"
    override val title = "WASP ESCORT DRONE"
    override val description = listOf(
        "Small escort drone that keeps close to your ship.",
        "Fires at the nearest enemy and blocks incoming shots with its hull.",
    )
    override val role = Role.MOB

    override val body = NpcBody(
        hitbox = Hitbox.Circle(0.18f),
        density = 2f,
        restitution = 0.2f,
        maxHealth = 20f,
        armor = 1f,
        physical = true,
        maxAccel = 12f,
    )

    override val behavior = Behavior(
        BehaviorPhase(
            movement = Movement.Escort(radius = 1f, height = 1.2f, speed = 5f),
            attacks = AttackPlan.Cycle(
                steps = listOf(
                    AttackStep(
                        // Цель может быть с любой стороны: сектор обстрела — полный круг.
                        Burst(ProjectileKind.ALLY_BOLT, count = 2, interval = 0.12f, speed = 12f, aim = Aim.AtTarget(halfAngle = PI.toFloat())),
                        pause = 0.8f,
                    ),
                ),
            ),
        ),
    )

    override val loot = NpcLoot(money = 0..0)
}

// --- Призывы игрока ---

/** Призывы игрока: что вызывает каждый источник (расходник, постоянный усилитель в слоте, предмет-маяк). */
object PlayerSummons {
    /** Расходник «вызов дрона»: один дрон на 20 с, не больше двух от расходников сразу. */
    val CALL = SummonSpec(Drone, count = 1, maxAlive = 2, lifetime = 20f, diesWithOwner = true)

    /** Перк «дроновый отсек»: пара дронов на 15 с, затем перезарядка слота. */
    val BAY = SummonSpec(Drone, count = 2, maxAlive = 2, lifetime = 15f, diesWithOwner = true)

    /** Маяк в хранилище: один постоянный дрон, переживает уровни; погиб — возрождается (Config.Summon.BEACON_RESPAWN). */
    val BEACON = SummonSpec(Drone, count = 1, maxAlive = 1, diesWithOwner = true, keepsBetweenLevels = true)

    fun specFor(source: PowerUpType): SummonSpec? = when (source) {
        PowerUpType.DroneCall -> CALL
        PowerUpType.PerkPickup(Perk.DRONE_BAY) -> BAY
        else -> null
    }
}
