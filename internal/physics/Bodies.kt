package com.spaceace.game.physics

import com.spaceace.game.Config
import com.spaceace.game.model.Entity
import com.spaceace.game.model.Faction
import com.spaceace.game.model.Field
import com.spaceace.game.model.Hitbox
import com.spaceace.game.model.Meteor
import com.spaceace.game.model.Npc
import com.spaceace.game.model.Player
import com.spaceace.game.model.PowerUp
import com.spaceace.game.model.Projectile
import com.spaceace.game.physics.CollisionGroup.ALLY
import com.spaceace.game.physics.CollisionGroup.BOSS
import com.spaceace.game.physics.CollisionGroup.CEILING
import com.spaceace.game.physics.CollisionGroup.ENEMY
import com.spaceace.game.physics.CollisionGroup.ENEMY_ENTERING
import com.spaceace.game.physics.CollisionGroup.ENEMY_HEAVY_PROJECTILE
import com.spaceace.game.physics.CollisionGroup.ENEMY_PROJECTILE
import com.spaceace.game.physics.CollisionGroup.FLOOR
import com.spaceace.game.physics.CollisionGroup.METEOR
import com.spaceace.game.physics.CollisionGroup.METEOR_ENTERING
import com.spaceace.game.physics.CollisionGroup.PLAYER
import com.spaceace.game.physics.CollisionGroup.PLAYER_PROJECTILE
import com.spaceace.game.physics.CollisionGroup.POWER_UP
import com.spaceace.game.physics.CollisionGroup.WALL

// --- Материалы и тела ---

enum class BodyKind { DYNAMIC, KINEMATIC }

/** Как сущность устроена в Box2D. Чистые данные, чтобы правила можно было проверить без нативного Box2D. */
data class Material(
    val density: Float,
    val restitution: Float,
    val friction: Float,
    val sensor: Boolean,
    val bodyKind: BodyKind,
    val bullet: Boolean,
    val fixedRotation: Boolean,
    val linearDamping: Float,
)

/**
 * Некруглые тела не вращаются: угол поворота не хранится в снимке сущности,
 * и повёрнутый хитбокс разошёлся бы с картинкой.
 */
fun materialOf(entity: Entity): Material = when (entity) {
    is Player -> Material(
        density = entity.ship.stats.density,
        restitution = entity.ship.stats.restitution,
        friction = Config.Physics.FRICTION,
        sensor = false,
        bodyKind = BodyKind.DYNAMIC,
        bullet = false,
        fixedRotation = true,
        linearDamping = Config.Player.LINEAR_DAMPING,
    )
    is Meteor -> Material(
        density = entity.size.stats.density,
        restitution = entity.size.stats.restitution,
        friction = Config.Physics.FRICTION,
        sensor = false,
        bodyKind = BodyKind.DYNAMIC,
        bullet = false,
        fixedRotation = entity.hitbox !is Hitbox.Circle,
        linearDamping = 0f,
    )
    // Нефизичный противник (в т. ч. босс) — кинематический: импульсы его не сдвигают, курс задаёт только ИИ.
    is Npc -> Material(
        density = entity.kind.body.density,
        restitution = entity.kind.body.restitution,
        friction = Config.Physics.FRICTION,
        sensor = false,
        bodyKind = if (entity.kind.body.physical) BodyKind.DYNAMIC else BodyKind.KINEMATIC,
        bullet = false,
        fixedRotation = !entity.kind.body.rotates,
        linearDamping = 0f,
    )
    is Projectile -> Material(
        density = entity.density,
        restitution = entity.kind.stats.restitution,
        friction = 0f,
        sensor = entity.kind.stats.piercing,
        bodyKind = BodyKind.DYNAMIC,
        bullet = true,
        fixedRotation = true,
        linearDamping = 0f,
    )
    // Кинематический сенсор: игрок не может его толкнуть, а контакт всё равно регистрируется.
    is PowerUp -> Material(
        density = Config.PowerUp.DENSITY,
        restitution = 0f,
        friction = 0f,
        sensor = true,
        bodyKind = BodyKind.KINEMATIC,
        bullet = false,
        fixedRotation = true,
        linearDamping = 0f,
    )
}

// --- Кто с кем сталкивается ---

/** Категория Box2D-фикстуры; бит = 1 shl ordinal (Box2D даёт 16 бит). */
enum class CollisionGroup {
    PLAYER, METEOR, BOSS, ENEMY, PLAYER_PROJECTILE, ENEMY_PROJECTILE, POWER_UP, WALL, CEILING,

    /** Пол только для корабля игрока: не даёт опуститься в полосу слотов, остальное пролетает насквозь. */
    FLOOR,

    /** Метеорит, ещё влетающий из-за бокового края: как METEOR, но стены его пропускают. */
    METEOR_ENTERING,

    /** Вражеский снаряд, который бьёт и метеориты (гаусс босса). */
    ENEMY_HEAVY_PROJECTILE,

    /** Противник, ещё влетающий из засады из-за бокового края: как ENEMY, но стены его пропускают. */
    ENEMY_ENTERING,

    /** Союзник-саммон игрока: закрывает от вражеских снарядов и таранов, не мешает кораблю игрока и его снарядам. */
    ALLY;

    val bit: Short get() = (1 shl ordinal).toShort()
}

data class FilterBits(val category: Short, val mask: Short)

/**
 * Кто с кем сталкивается. Задано парами, поэтому маски симметричны по построению —
 * Box2D требует, чтобы обе стороны «согласились» на контакт.
 */
private val collidingPairs: Set<Set<CollisionGroup>> = setOf(
    setOf(PLAYER, METEOR),
    setOf(PLAYER, ENEMY_PROJECTILE),
    setOf(PLAYER, ENEMY),
    setOf(PLAYER, POWER_UP),
    setOf(PLAYER, WALL),
    setOf(PLAYER, FLOOR),
    setOf(METEOR), // метеорит с метеоритом
    setOf(METEOR, PLAYER_PROJECTILE),
    setOf(METEOR, WALL),
    setOf(BOSS, PLAYER_PROJECTILE),
    setOf(BOSS, METEOR), // босс кинематический: метеориты отскакивают от него, урон — по формуле энергии
    setOf(ENEMY), // вражеские корабли расталкивают друг друга
    setOf(ENEMY, METEOR),
    setOf(ENEMY, PLAYER_PROJECTILE),
    setOf(ENEMY, WALL),
    setOf(PLAYER_PROJECTILE, WALL),
    setOf(PLAYER_PROJECTILE, CEILING),
    setOf(ENEMY_HEAVY_PROJECTILE, PLAYER),
    setOf(ENEMY_HEAVY_PROJECTILE, METEOR),
    setOf(ALLY), // союзники расталкивают друг друга
    setOf(ALLY, ENEMY),
    setOf(ALLY, BOSS),
    setOf(ALLY, METEOR),
    setOf(ALLY, ENEMY_PROJECTILE),
    setOf(ALLY, ENEMY_HEAVY_PROJECTILE),
    setOf(ALLY, WALL),
)

/** Влетающие из-за края (метеорит, противник) сталкиваются со всем, с чем обычные, кроме стен. */
fun collides(a: CollisionGroup, b: CollisionGroup): Boolean {
    if (a.isEntering && b == WALL || b.isEntering && a == WALL) return false
    return setOf(a.asSolid(), b.asSolid()) in collidingPairs
}

private val CollisionGroup.isEntering get() = this == METEOR_ENTERING || this == ENEMY_ENTERING

private fun CollisionGroup.asSolid() = when (this) {
    METEOR_ENTERING -> METEOR
    ENEMY_ENTERING -> ENEMY
    else -> this
}

fun filterFor(group: CollisionGroup): FilterBits {
    val mask = CollisionGroup.entries
        .filter { collides(group, it) }
        .fold(0) { acc, other -> acc or other.bit.toInt() }
    return FilterBits(category = group.bit, mask = mask.toShort())
}

fun collisionGroupOf(entity: Entity): CollisionGroup = when (entity) {
    is Player -> CollisionGroup.PLAYER
    is Meteor -> if (entity.entering) METEOR_ENTERING else METEOR
    is Npc -> when {
        entity.isAlly -> ALLY
        entity.isBoss -> CollisionGroup.BOSS
        entity.entry != null -> ENEMY_ENTERING
        else -> CollisionGroup.ENEMY
    }
    is PowerUp -> CollisionGroup.POWER_UP
    is Projectile -> when (entity.faction) {
        Faction.PLAYER -> CollisionGroup.PLAYER_PROJECTILE
        Faction.ENEMY -> if (entity.kind.stats.hitsMeteors) ENEMY_HEAVY_PROJECTILE else CollisionGroup.ENEMY_PROJECTILE
    }
}

// --- Границы поля ---

/**
 * Покинула ли сущность поле окончательно. Выше поля метеориты и усилители появляются,
 * поэтому для них верхняя граница отодвинута на [ceilingMargin] (метеорит, отброшенный пулями вверх).
 */
fun leftField(entity: Entity, field: Field, ceilingMargin: Float): Boolean {
    val p = entity.position
    val r = entity.radius
    val belowBottom = p.y + r < 0f
    return when (entity) {
        is Player -> false
        is Npc -> !entity.isBoss && (belowBottom || p.y - r > field.height + ceilingMargin)
        is Meteor, is PowerUp -> belowBottom || p.y - r > field.height + ceilingMargin
        is Projectile -> belowBottom || p.y - r > field.height || p.x + r < 0f || p.x - r > field.width
    }
}
