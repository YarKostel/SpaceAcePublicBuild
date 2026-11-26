package com.spaceace.game.npc

import com.spaceace.game.Config
import com.spaceace.game.input.steeringImpulse
import com.spaceace.game.model.Entity
import com.spaceace.game.model.Faction
import com.spaceace.game.model.Npc
import com.spaceace.game.model.NpcKind
import com.spaceace.game.model.SummonSpec
import com.spaceace.game.model.Vec2
import com.spaceace.game.model.newProjectile
import com.spaceace.game.model.newSummon
import com.spaceace.game.physics.PhysicsSystem
import com.spaceace.game.state.GameState
import kotlin.random.Random
/**
 * Противники и союзники на поле: каждый шаг (до физики) спрашивает ИИ и применяет решение к реестру и Box2D.
 * Физичное тело ведётся импульсами с ограниченным ускорением — толчки гаснут, а не стираются;
 * нефизичному (кинематическому) скорость задаётся напрямую. Влетающий из засады ИИ не слушает, пока не долетит.
 * Саммоны стареют и исчезают по своим правилам; призывы исполняются здесь же с учётом лимитов.
 */
class NpcSystem(private val state: GameState, private val physics: PhysicsSystem, private val random: Random) {
    private val field = Config.Physics.FIELD

    fun step(dt: Float) {
        val player = state.player
        val everyone = state.entities.all.toList()
        for (original in state.entities.ofType<Npc>()) {
            val npc = age(original, dt) ?: continue
            if (npc.entry != null) {
                val velocity = entryVelocity(npc)
                if (velocity == null) state.entities.replace(npc.arrived()) else drive(npc, velocity, null, dt)
                continue
            }
            val owner = npc.summon?.owner?.let { state.entities[it] }
            val target = if (npc.isAlly) allyTarget(npc.position, everyone, field) else player
            val orders = think(npc, Sight(target, field, owner), dt, random)
            state.entities.replace(npc.withBrain(orders.brain))
            drive(npc, orders.velocity, orders.angle, dt)
            for (shot in orders.shots) state.entities.spawn { newProjectile(it, shot.kind, shot.position, shot.velocity) }
            for (spec in orders.summons) summon(npc, spec, npc.faction)
            orders.beams.forEach(state.hazards::fire)
            orders.zones.forEach(state.hazards::place)
        }
    }

    /** Призыв от имени корабля игрока (слот, расходник, предмет). */
    fun summonForPlayer(spec: SummonSpec): List<Npc> = state.player?.let { summon(it, spec, Faction.PLAYER) } ?: emptyList()

    /** Сколько живо саммонов, призванных по [spec] игроком. */
    fun playerSummons(spec: SummonSpec): Int {
        val player = state.player ?: return 0
        return state.entities.ofType<Npc>().count { it.summon?.owner == player.id && it.summon.spec == spec }
    }

    /** Убит противник: боссы учитывают это для переходов «убить N». */
    fun recordKill(kind: NpcKind) {
        for (boss in state.entities.ofType<Npc>().filter { it.isBoss }) {
            state.entities.replace(boss.withBrain(boss.brain.recordKill(kind.id)))
        }
    }

    /** Лимиты: не больше [SummonSpec.maxAlive] по этому призыву у призывателя и не больше общего потолка на поле. */
    private fun summon(owner: Entity, spec: SummonSpec, faction: Faction): List<Npc> {
        val summons = state.entities.ofType<Npc>().filter { it.summon != null }
        val mine = summons.count { it.summon?.owner == owner.id && it.summon.spec == spec }
        val room = summonRoom(spec, mine, summons.size)
        return summonPositions(owner.position, room, spec.spread, random).map { position ->
            state.entities.spawn { newSummon(it, spec, owner.id, faction, position) }
        }
    }

    /** Саммон стареет; истёк или потерял призывателя, которому должен следовать, — исчезает (null). */
    private fun age(npc: Npc, dt: Float): Npc? {
        val tie = npc.summon ?: return npc
        val aged = npc.aged(dt)
        if (shouldVanish(aged, tie.owner in state.entities)) {
            state.entities.remove(npc.id)
            return null
        }
        state.entities.replace(aged)
        return aged
    }

    private fun drive(npc: Npc, velocity: Vec2, angle: Float?, dt: Float) {
        val body = npc.kind.body
        if (body.physical) {
            val impulse = Vec2(
                steeringImpulse(npc.mass, npc.velocity.x, velocity.x, body.maxAccel, dt),
                steeringImpulse(npc.mass, npc.velocity.y, velocity.y, body.maxAccel, dt),
            )
            physics.applyImpulse(npc.id, impulse)
        } else {
            physics.setVelocity(npc.id, velocity)
        }
        if (body.rotates) angle?.let { physics.turnTo(npc.id, it, dt) }
    }
}
