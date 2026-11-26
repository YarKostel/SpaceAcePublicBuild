package com.spaceace.game.content

import com.spaceace.game.model.Hitbox
import com.spaceace.game.model.NpcBody
import com.spaceace.game.model.NpcKind
import com.spaceace.game.model.NpcLoot
import com.spaceace.game.model.ProjectileKind
import com.spaceace.game.model.Role
import com.spaceace.game.model.Rotation
import com.spaceace.game.model.degrees
import com.spaceace.game.npc.Aim
import com.spaceace.game.npc.AttackPlan
import com.spaceace.game.npc.AttackStep
import com.spaceace.game.npc.Behavior
import com.spaceace.game.npc.BehaviorPhase
import com.spaceace.game.npc.Burst
import com.spaceace.game.npc.Movement

/**
 * Базовый противник. Держится над игроком на расстоянии, выравнивается с ним по X (вверх не летает, может зависнуть)
 * и стреляет очередями по 3 пули, только когда игрок в секторе обстрела 80° (±40° от направления вниз).
 * Игрок оказался выше — уходит вниз и исчезает за нижним краем. Появляется по одному или парами (см. Levels).
 */
object Gravel : NpcKind {
    override val id = "gravel"
    override val title = "GRAVEL-CLASS PATROL INTERCEPTOR"
    override val description = listOf(
        "Cheap and numerous patrol interceptor.",
        "Keeps its distance and only fires when you are inside its 80-degree firing arc.",
    )
    override val role = Role.MOB

    override val body = NpcBody(
        hitbox = Hitbox.Triangle(base = 0.7f, height = 0.8f, rotation = Rotation.R180),
        density = 2f,
        restitution = 0.3f,
        maxHealth = 16f,
        armor = 1f,
        physical = true,
        maxAccel = 8f,
    )

    override val behavior = Behavior(
        BehaviorPhase(
            movement = Movement.Stalk(distance = 4.5f, speedX = 2f, descendSpeed = 1.6f, leaveSpeed = 2.5f),
            attacks = AttackPlan.Cycle(
                steps = listOf(
                    AttackStep(
                        Burst(ProjectileKind.ENEMY_BOLT, count = 3, interval = 0.15f, speed = 6f, aim = Aim.AtTarget(halfAngle = degrees(40f))),
                        pause = 2f,
                    ),
                ),
                startDelay = 1f,
            ),
        ),
    )

    override val loot = NpcLoot(money = 3..6, powerUpChance = 0.15f)
}
