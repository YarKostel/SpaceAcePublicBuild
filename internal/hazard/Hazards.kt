package com.spaceace.game.hazard

import com.spaceace.game.Config
import com.spaceace.game.model.Damageable
import com.spaceace.game.model.Entity
import com.spaceace.game.model.EntityId
import com.spaceace.game.model.Faction
import com.spaceace.game.model.Meteor
import com.spaceace.game.model.Npc
import com.spaceace.game.model.Player
import com.spaceace.game.model.Vec2
import com.spaceace.game.model.rotatedBy
import com.spaceace.game.physics.DamageSettings
import com.spaceace.game.physics.takenScale
import com.spaceace.game.state.GameState
import kotlin.math.sqrt

// --- Данные лучей и зон ---

/*
 * Лучи и опасные зоны: не тела Box2D, а данные на доске опасностей ([HazardBoard] ниже).
 * Урон — энергией: мощность P (Дж/с) за шаг даёт энергию P·dt, дальше обычная формула (×K ×броня), без порога.
 */

/**
 * Лазерный луч: мощность [power] Дж/с, длина [length] м, толщина [width] м (для отрисовки); первые [warmup] с —
 * предупреждение без урона.
 */
data class BeamSpec(val power: Float, val length: Float, val width: Float, val warmup: Float = 0f) {
    init {
        require(power >= 0f && length > 0f && width > 0f && warmup >= 0f) { "invalid beam: $this" }
    }
}

/** Заявка на луч в этом шаге: откуда, куда (единичный вектор), чей, идёт ли урон ([live]) или это предупреждение. */
data class BeamShot(
    val owner: EntityId,
    val faction: Faction,
    val origin: Vec2,
    val direction: Vec2,
    val spec: BeamSpec,
    val live: Boolean,
)

/** Луч после расчёта: где обрывается ([end]) и в кого упёрся ([target]; null — ни в кого). Для урона и отрисовки. */
data class Beam(val shot: BeamShot, val end: Vec2, val target: EntityId?)

/**
 * Опасная зона-круг радиуса [radius]: мощность [power] Дж/с по каждому, кто в ней, если его сторона в [affects];
 * живёт [lifetime] с, первые [warmup] с — предупреждение без урона. Метеориты (у них нет стороны) зоны не трогают.
 */
data class ZoneSpec(
    val radius: Float,
    val power: Float,
    val lifetime: Float,
    val affects: Set<Faction>,
    val warmup: Float = 0f,
) {
    init {
        require(radius > 0f && power >= 0f && lifetime > 0f && warmup in 0f..lifetime) { "invalid zone: $this" }
    }
}

/** Зона на поле; [faction] — чья (для цвета), [age] — сколько секунд существует. */
data class Zone(val center: Vec2, val spec: ZoneSpec, val faction: Faction, val age: Float = 0f) {
    val live: Boolean get() = age >= spec.warmup
    val isExpired: Boolean get() = age >= spec.lifetime

    fun aged(dt: Float) = copy(age = age + dt)
}

// --- Доска опасностей ---

/**
 * Лучи и опасные зоны на поле. Лучи заявляются каждый шаг заново (оружие, атаки противников) и рассчитываются
 * HazardSystem; зоны живут своё время. Рендер только читает [beams] и [zones].
 */
class HazardBoard {
    private val requested = ArrayList<BeamShot>()

    /** Рассчитанные в последнем шаге лучи. */
    var beams: List<Beam> = emptyList()
        private set
    var zones: List<Zone> = emptyList()
        private set

    fun fire(shot: BeamShot) {
        requested += shot
    }

    fun place(zone: Zone) {
        zones = zones + zone
    }

    fun takeRequested(): List<BeamShot> = requested.toList().also { requested.clear() }

    fun show(beams: List<Beam>) {
        this.beams = beams
    }

    fun replaceZones(zones: List<Zone>) {
        this.zones = zones
    }

    fun clear() {
        requested.clear()
        beams = emptyList()
        zones = emptyList()
    }
}

// --- Геометрия и урон (чистые функции) ---

/** Сторона сущности для лучей и зон; null — нейтральная (метеориты). */
fun factionOf(entity: Entity): Faction? = when (entity) {
    is Player -> Faction.PLAYER
    is Npc -> entity.faction
    else -> null
}

/** Луч упирается в метеориты и корпуса другой стороны; свои и снаряды с усилителями пропускает. */
fun blocksBeam(entity: Entity, beamFaction: Faction): Boolean = when (entity) {
    is Meteor -> true
    is Player, is Npc -> factionOf(entity) != beamFaction
    else -> false
}

/**
 * Расстояние вдоль луча (из [origin] по единичному [direction], не дальше [length]) до хитбокса [entity];
 * null — не задевает. Начало внутри фигуры — 0. Многоугольники поворачиваются вместе с корпусом противника.
 */
fun rayHit(origin: Vec2, direction: Vec2, length: Float, entity: Entity): Float? {
    val vertices = entity.hitbox.vertices ?: return rayCircle(origin, direction, length, entity.position, entity.hitbox.boundingRadius)
    val angle = (entity as? Npc)?.angle ?: 0f
    return rayPolygon(origin, direction, length, vertices.map { entity.position + it.rotatedBy(angle) })
}

/** Первое препятствие на луче; без препятствий луч идёт на всю длину. */
fun castBeam(shot: BeamShot, entities: Collection<Entity>): Beam {
    var nearest: Entity? = null
    var distance = shot.spec.length
    for (entity in entities) {
        if (!blocksBeam(entity, shot.faction)) continue
        val hit = rayHit(shot.origin, shot.direction, distance, entity) ?: continue
        if (hit < distance) {
            distance = hit
            nearest = entity
        }
    }
    return Beam(shot, shot.origin + shot.direction * distance, nearest?.id)
}

/** Задевает ли зона сущность: круг зоны пересекается с описанной окружностью хитбокса. */
fun zoneTouches(zone: Zone, entity: Entity): Boolean {
    val reach = zone.spec.radius + entity.radius
    return (entity.position - zone.center).lengthSquared <= reach * reach
}

/** Действует ли зона на сущность: её сторона в списке зоны (метеориты — никогда). */
fun zoneAffects(zone: Zone, entity: Entity): Boolean = factionOf(entity)?.let { it in zone.spec.affects } ?: false

/** Урон от энергии [energy] (мощность × dt) с учётом брони; порога нет — иначе непрерывный луч не ранил бы. */
fun hazardDamage(energy: Float, target: Damageable, settings: DamageSettings): Float = settings.coefficient * energy * target.armor

private fun rayCircle(origin: Vec2, direction: Vec2, length: Float, center: Vec2, radius: Float): Float? {
    val toOrigin = origin - center
    val b = toOrigin dot direction
    val c = toOrigin.lengthSquared - radius * radius
    if (c <= 0f) return 0f
    val discriminant = b * b - c
    if (b > 0f || discriminant < 0f) return null
    val t = -b - sqrt(discriminant)
    return t.takeIf { it in 0f..length }
}

/** Отсечение отрезка выпуклым многоугольником (вершины против часовой стрелки; внешняя нормаль ребра a→b — (dy, −dx)). */
private fun rayPolygon(origin: Vec2, direction: Vec2, length: Float, vertices: List<Vec2>): Float? {
    var enter = 0f
    var exit = length
    for (i in vertices.indices) {
        val a = vertices[i]
        val b = vertices[(i + 1) % vertices.size]
        val normal = Vec2(b.y - a.y, -(b.x - a.x))
        val distance = normal dot (a - origin)
        val speed = normal dot direction
        if (speed == 0f) {
            if (distance < 0f) return null
            continue
        }
        val t = distance / speed
        if (speed < 0f) enter = maxOf(enter, t) else exit = minOf(exit, t)
        if (enter > exit) return null
    }
    return enter
}

// --- Система ---

/**
 * Лучи и опасные зоны на один шаг (до физики): рассчитывает заявленные лучи (первое препятствие), старит зоны,
 * наносит урон энергией мощность × dt. Уничтоженных (кроме корабля игрока) убирает из реестра и возвращает —
 * дроп, трофеи и game over решает игровая логика, как для ударов.
 */
class HazardSystem(private val state: GameState, private val damage: DamageSettings = Config.Damage.SETTINGS) {

    fun step(dt: Float): List<Damageable> {
        val entities = state.entities.all.toList()
        val beams = state.hazards.takeRequested().map { castBeam(it, entities) }
        state.hazards.show(beams)
        val zones = state.hazards.zones.map { it.aged(dt) }.filterNot { it.isExpired }
        state.hazards.replaceZones(zones)

        val damages = LinkedHashMap<EntityId, Float>()
        for (beam in beams) {
            if (!beam.shot.live) continue
            val target = beam.target?.let { state.entities[it] } as? Damageable ?: continue
            val amount = hazardDamage(beam.shot.spec.power * dt, target, damage) * takenScale(target, beam.end)
            damages.merge(target.id, amount, Float::plus)
        }
        for (zone in zones) {
            if (!zone.live) continue
            for (entity in entities) {
                if (entity !is Damageable || !zoneAffects(zone, entity) || !zoneTouches(zone, entity)) continue
                damages.merge(entity.id, hazardDamage(zone.spec.power * dt, entity, damage), Float::plus)
            }
        }
        return apply(damages)
    }

    private fun apply(damages: Map<EntityId, Float>): List<Damageable> {
        val destroyed = ArrayList<Damageable>()
        for ((id, amount) in damages) {
            val target = state.entities[id] as? Damageable ?: continue
            if (amount <= 0f || target.health.isDepleted) continue
            val hurt = target.withHealth(target.health.damaged(amount))
            state.entities.replace(hurt)
            if (hurt.health.isDepleted) {
                destroyed += hurt
                if (hurt !is Player) state.entities.remove(id)
            }
        }
        return destroyed
    }
}
