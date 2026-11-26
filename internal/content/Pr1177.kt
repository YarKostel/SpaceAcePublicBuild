package com.spaceace.game.content

import com.spaceace.game.Config
import com.spaceace.game.model.Hitbox
import com.spaceace.game.model.NpcBody
import com.spaceace.game.model.NpcKind
import com.spaceace.game.model.NpcLoot
import com.spaceace.game.model.Role
import com.spaceace.game.npc.Behavior
import com.spaceace.game.npc.BehaviorPhase
import com.spaceace.game.npc.Movement

/**
 * Черновик босса (в работе): пока одна фаза — подлёт и зависание, без атак. Фазы с атаками — в комментарии внизу,
 * их доделка отложена (захват и бросок метеоритов, ангар Borer).
 */
object Pr1177 : NpcKind {

    override val id = "pr1177"
    override val title = "PR-1177 MOBILE MINING STATION"
    override val description = listOf(
        "Mobile space mining station operated by KDC subsidiaries in planetary asteroid belts.",
        "Equipped with a powerful gravity generator for pulling in meteoroids, several industrial mining laser mounts, and hangars for the Mk-12 Space Borer.",
        "Manufactured by Kubis."
    )
    override val role = Role.BOSS

    /** Диаметр станции — доля ширины экрана. */
    private const val RADIUS = Config.Physics.WORLD_WIDTH / 2f
    private const val ENTRY_SPEED = 2f

    val HOVER_Y_1 = (Config.Physics.WORLD_HEIGHT / 2f) - 0.2f
    val HOVER_X_1 = Config.Physics.WORLD_WIDTH / 2f

    /** Центр у верхнего края: станция свисает с него полусферой. */
    val HOVER_Y_2 = Config.Physics.WORLD_HEIGHT - 0.2f
    val HOVER_X_2 = Config.Physics.WORLD_WIDTH / 2f

    override val body = NpcBody(
        hitbox = Hitbox.Circle(RADIUS),
        density = 3f,
        restitution = 0f,
        maxHealth = 8000f,
        armor = 0.8f,
        physical = false,
        maxAccel = 10f
    )

    private val hover = Movement.Hover(entrySpeed = ENTRY_SPEED, hoverY = HOVER_Y_2, hoverX = HOVER_X_2)

    override val behavior = Behavior(listOf(BehaviorPhase(movement = hover)))

    override val loot = NpcLoot(money = 60..60, trophy = true)

    /*
     * Черновик (отложено):
     *
     * override val behavior = Behavior(
     *     listOf(
     *         BehaviorPhase(movement = hover, attacks = AttackPlan.Custom(::), environment = Environment.SideMeteors(sideMeteors), exit = Transition.HealthBelow(RAGE_HEALTH)),
     *         BehaviorPhase(movement = patrol, attacks = AttackPlan.Custom(::), environment = Environment.SideMeteors(sideMeteors)),
     *     ),
     * )
     *
     * internal fun MeteorPullNPush(input: NpcInput): AttackResult {
     *     val npc = input.npc
     *     val state = npc.brain.attack
     *     val player = input.sight.target ?: return AttackResult(emptyList(), state.copy(memory = AlignMemory()))
     *     val overlap = abs(player.position.x - npc.position.x) < npc.hitbox.halfWidth + player.hitbox.halfWidth
     *     val aligned = if (overlap) ((state.memory as? AlignMemory)?.aligned ?: 0f) + input.dt else 0f
     *     if (aligned < ALIGN_TIME) return AttackResult(emptyList(), state.copy(memory = AlignMemory(aligned)))
     *     val shot = aimedShot(npc, ProjectileKind.ENEMY_GAUSS_SLUG, player.position - npc.position, GAUSS_SPEED)
     *     return AttackResult(listOf(shot), state.copy(memory = AlignMemory()))
     * }
     */
}
