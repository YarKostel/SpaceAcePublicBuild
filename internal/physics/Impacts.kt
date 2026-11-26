package com.spaceace.game.physics

import com.spaceace.game.Config
import com.spaceace.game.model.Damageable
import com.spaceace.game.model.Entity
import com.spaceace.game.model.EntityId
import com.spaceace.game.model.Faction
import com.spaceace.game.model.HitZone
import com.spaceace.game.model.Npc
import com.spaceace.game.model.Player
import com.spaceace.game.model.PowerUp
import com.spaceace.game.model.Projectile
import com.spaceace.game.model.Vec2
import com.spaceace.game.model.noseDirection
import com.spaceace.game.model.signedAngle
import com.spaceace.game.model.wrapAngle
import com.spaceace.game.weapon.PierceSettings
import com.spaceace.game.weapon.pierce
import kotlin.math.abs
import kotlin.math.max

// --- Урон ---

data class DamageSettings(val coefficient: Float, val energyThreshold: Float) {
    init {
        require(coefficient >= 0f && energyThreshold >= 0f) { "invalid damage settings: $this" }
    }
}

/** μ = m₁m₂ / (m₁ + m₂). */
fun reducedMass(m1: Float, m2: Float): Float {
    require(m1 > 0f && m2 > 0f) { "masses must be positive: $m1, $m2" }
    return m1 * m2 / (m1 + m2)
}

/** Кинетическая энергия удара в системе центра масс: μ·|v_rel|²/2. */
fun impactEnergy(m1: Float, m2: Float, relativeVelocity: Vec2): Float =
    reducedMass(m1, m2) * relativeVelocity.lengthSquared / 2f

/** damage = K × E × armor; удары слабее порога урона не наносят. */
fun impactDamage(energy: Float, armor: Float, settings: DamageSettings): Float =
    if (energy < settings.energyThreshold) 0f else settings.coefficient * energy * armor

/**
 * Box2D по умолчанию берёт max(restitution) пары, и упругий метеорит делал бы упругим любой снаряд.
 * Если в паре есть снаряд — решает его restitution.
 */
fun mixRestitution(first: Float, second: Float, projectileRestitution: Float?): Float =
    projectileRestitution ?: max(first, second)

// --- Зоны корпуса ---

/** Секторы корпуса противника, в которые пришёлся удар в точке [point] (в осях поля). */
fun zonesAt(npc: Npc, point: Vec2): List<HitZone> {
    val zones = npc.kind.body.zones
    if (zones.isEmpty()) return emptyList()
    val bearing = signedAngle(noseDirection(npc.angle), point - npc.position)
    return zones.filter { abs(wrapAngle(bearing - it.center)) <= it.halfAngle }
}

/** Множитель урона, который [entity] получает от удара в [point]. */
fun takenScale(entity: Entity, point: Vec2): Float =
    (entity as? Npc)?.let { npc -> zonesAt(npc, point).fold(1f) { acc, zone -> acc * zone.takenScale } } ?: 1f

/** Множитель урона, который [entity] наносит тому, в кого врезался точкой [point]. */
fun dealtScale(entity: Entity, point: Vec2): Float =
    (entity as? Npc)?.let { npc -> zonesAt(npc, point).fold(1f) { acc, zone -> acc * zone.dealtScale } } ?: 1f

// --- Разбор ударов ---

/**
 * Начало контакта, записанное до разрешения столкновения (скорости — до удара).
 * [second] == null — граница поля (стена или потолок). [point] — точка контакта; у сенсоров её нет —
 * тогда берётся середина между центрами.
 */
data class ImpactEvent(
    val first: EntityId,
    val second: EntityId?,
    val firstVelocity: Vec2,
    val secondVelocity: Vec2,
    val point: Vec2? = null,
)

data class Pickup(val player: EntityId, val powerUp: EntityId)

data class ImpactOutcome(
    /** Новые снимки изменившихся сущностей (HP, отскоки). */
    val updated: List<Entity>,
    val removed: Set<EntityId>,
    val impulses: Map<EntityId, Vec2>,
    val velocityOverrides: Map<EntityId, Vec2>,
    /** Сущности, у которых HP только что дошло до нуля (для дропа на этапе 5). Игрок не удаляется. */
    val destroyed: List<Damageable>,
    val pickups: List<Pickup>,
) {
    val playerDestroyed: Boolean get() = destroyed.any { it is Player }
}

/**
 * Применяет игровые последствия контактов: урон по формуле энергии (с множителями секторов корпуса — таран носом,
 * бронированный фронт), расход отскоков, пробитие, подбор усилителей. Физический обмен импульсом обычных тел уже
 * сделал Box2D; здесь добавляется только импульс пробивающих снарядов (они сенсоры).
 */
class ImpactResolver(
    private val damage: DamageSettings,
    private val pierce: PierceSettings,
    private val shieldPower: Float = Config.PowerUp.SHIELD_BREAK_POWER,
) {

    fun resolve(events: List<ImpactEvent>, lookup: (EntityId) -> Entity?): ImpactOutcome =
        Resolution(lookup).apply { events.forEach(::handle) }.toOutcome()

    private inner class Resolution(private val lookup: (EntityId) -> Entity?) {
        private val working = LinkedHashMap<EntityId, Entity>()
        private val removed = LinkedHashSet<EntityId>()
        private val impulses = LinkedHashMap<EntityId, Vec2>()
        private val velocities = LinkedHashMap<EntityId, Vec2>()
        private val destroyed = ArrayList<Damageable>()
        private val pickups = ArrayList<Pickup>()

        private fun current(id: EntityId): Entity? = if (id in removed) null else working[id] ?: lookup(id)

        fun handle(event: ImpactEvent) {
            val a = current(event.first) ?: return
            val b = event.second?.let { current(it) ?: return }
            val va = velocities[a.id] ?: event.firstVelocity
            val vb = b?.let { velocities[it.id] } ?: event.secondVelocity
            val point = event.point ?: b?.let { (a.position + it.position) * HALF } ?: a.position

            when {
                a is Projectile -> projectileHit(a, va, b, vb, point)
                b is Projectile -> projectileHit(b, vb, a, va, point)
                a is Player && b is PowerUp -> pickups += Pickup(a.id, b.id)
                a is PowerUp && b is Player -> pickups += Pickup(b.id, a.id)
                a is Damageable && b is Damageable -> {
                    val energy = impactEnergy(a.mass, b.mass, va - vb)
                    hurt(a, energy * dealtScale(b, point), point)
                    hurt(b, energy * dealtScale(a, point), point)
                }
            }
        }

        private fun projectileHit(projectile: Projectile, pv: Vec2, target: Entity?, tv: Vec2, point: Vec2) {
            // Щит обнуляет урон; снаряд не сильнее щита поглощается, сильнее — пролетает насквозь (контакт уже отключён).
            if (target is Player && target.shielded && projectile.faction == Faction.ENEMY) {
                if (projectile.kind.stats.breakPower <= shieldPower) removed += projectile.id
                return
            }
            if (target is Damageable) hurt(target, impactEnergy(projectile.mass, target.mass, pv - tv), point)

            if (projectile.kind.stats.piercing && target is Damageable) {
                val result = pierce(projectile.mass, pv, pierce)
                val pushes = target !is Player || projectile.kind.stats.pushesPlayer
                if (pushes) impulses.merge(target.id, result.impulse, Vec2::plus)
                val slowed = result.velocity
                if (slowed == null) removed += projectile.id else velocities[projectile.id] = slowed
            } else if (projectile.bouncesLeft > 0) {
                working[projectile.id] = projectile.bounced()
            } else {
                removed += projectile.id
            }
        }

        private fun hurt(target: Damageable, energy: Float, point: Vec2) {
            val amount = impactDamage(energy, target.armor, damage) * takenScale(target, point)
            if (amount <= 0f || target.health.isDepleted) return
            val hurt = target.withHealth(target.health.damaged(amount))
            working[target.id] = hurt
            if (hurt.health.isDepleted) {
                destroyed += hurt
                if (hurt !is Player) removed += hurt.id
            }
        }

        fun toOutcome() = ImpactOutcome(
            updated = working.values.filter { it.id !in removed },
            removed = removed.toSet(),
            impulses = impulses.filterKeys { it !in removed },
            velocityOverrides = velocities.filterKeys { it !in removed },
            destroyed = destroyed.toList(),
            pickups = pickups.toList(),
        )
    }

    private companion object {
        const val HALF = 0.5f
    }
}
