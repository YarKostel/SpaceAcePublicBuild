package com.spaceace.game.model

import com.spaceace.game.Config
import com.spaceace.game.npc.AttackState
import com.spaceace.game.npc.Behavior
import com.spaceace.game.npc.MoveState
import kotlin.math.PI

// --- Идентификатор ---

@JvmInline
value class EntityId(val value: Int)

// --- Здоровье ---

data class Health(val current: Float, val max: Float) {
    init {
        require(max > 0f) { "max health must be positive: $max" }
        require(current in 0f..max) { "current health $current out of [0; $max]" }
    }

    val isDepleted: Boolean get() = current <= 0f
    val fraction: Float get() = current / max

    fun damaged(amount: Float): Health {
        require(amount >= 0f) { "damage must be non-negative: $amount" }
        return copy(current = (current - amount).coerceAtLeast(0f))
    }

    fun healed(amount: Float): Health {
        require(amount >= 0f) { "heal must be non-negative: $amount" }
        return copy(current = (current + amount).coerceAtMost(max))
    }

    companion object {
        fun full(max: Float) = Health(max, max)
    }
}

// --- Сущности ---

/**
 * Неизменяемый снимок игрового объекта. Координаты — метры поля (начало в левом нижнем углу, Y вверх).
 * Форма, масса и броня выводятся из типа, чтобы снимок не мог разойтись с параметрами в [Config].
 */
sealed interface Entity {
    val id: EntityId
    val position: Vec2
    val velocity: Vec2
    val hitbox: Hitbox
    val mass: Float

    /** Радиус описанной окружности хитбокса. */
    val radius: Float get() = hitbox.boundingRadius

    fun moved(position: Vec2, velocity: Vec2): Entity
}

/** Сущность с HP. [armor] — множитель входящего урона (1 — без брони). */
sealed interface Damageable : Entity {
    val health: Health
    val armor: Float

    fun withHealth(health: Health): Damageable
}

data class Player(
    override val id: EntityId,
    val ship: ShipType,
    override val position: Vec2,
    override val velocity: Vec2,
    override val health: Health,
    val shielded: Boolean = false,
) : Damageable {
    override val hitbox: Hitbox get() = ship.stats.hitbox
    override val mass: Float = ship.stats.hitbox.area * ship.stats.density
    override val armor: Float get() = if (shielded) 0f else ship.stats.armor

    override fun moved(position: Vec2, velocity: Vec2) = copy(position = position, velocity = velocity)
    override fun withHealth(health: Health) = copy(health = health)
    fun withShield(shielded: Boolean) = copy(shielded = shielded)
}

/** [entering] — ещё влетает из-за бокового края: стены его пропускают, пока он целиком не окажется на поле. */
data class Meteor(
    override val id: EntityId,
    val size: MeteorSize,
    override val position: Vec2,
    override val velocity: Vec2,
    override val health: Health,
    val entering: Boolean = false,
) : Damageable {
    override val hitbox: Hitbox get() = size.stats.hitbox
    override val mass: Float = size.stats.hitbox.area * size.stats.density
    override val armor: Float get() = size.stats.armor

    override fun moved(position: Vec2, velocity: Vec2) = copy(position = position, velocity = velocity)
    override fun withHealth(health: Health) = copy(health = health)
    fun entered() = copy(entering = false)
}

/**
 * Противник любого вида (моб или босс) или союзник-саммон игрока ([faction] = PLAYER). Всё постоянное — в [kind];
 * [angle] — поворот носа против часовой стрелки, рад (0 — нос вверх по полю), у невращающихся тел постоянный;
 * [summon] — связь с призывателем (null — появился сам, не призван).
 */
data class Npc(
    override val id: EntityId,
    val kind: NpcKind,
    override val position: Vec2,
    override val velocity: Vec2,
    override val health: Health,
    val angle: Float = kind.body.startAngle,
    val brain: Brain = Brain(),
    val entry: Entry? = null,
    val faction: Faction = Faction.ENEMY,
    val summon: SummonTie? = null,
) : Damageable {
    override val hitbox: Hitbox get() = kind.body.hitbox
    override val mass: Float = kind.body.hitbox.area * kind.body.density
    override val armor: Float get() = kind.body.armor
    val role: Role get() = kind.role
    val isBoss: Boolean get() = kind.role == Role.BOSS && faction == Faction.ENEMY && summon == null
    val isAlly: Boolean get() = faction == Faction.PLAYER

    override fun moved(position: Vec2, velocity: Vec2) = copy(position = position, velocity = velocity)
    override fun withHealth(health: Health) = copy(health = health)
    fun withBrain(brain: Brain) = copy(brain = brain)
    fun turned(angle: Float) = copy(angle = angle)
    fun arrived() = copy(entry = null)
    fun aged(dt: Float) = summon?.let { copy(summon = it.copy(age = it.age + dt)) } ?: this
}

data class Projectile(
    override val id: EntityId,
    val kind: ProjectileKind,
    override val position: Vec2,
    override val velocity: Vec2,
    val bouncesLeft: Int = kind.stats.maxBounces,
    val age: Float = 0f,
    /** Множитель плотности (усилитель «тяжёлые снаряды»). */
    val massScale: Float = 1f,
    /** Цель самонаводящегося снаряда; выбирается заново, если цель уничтожена. */
    val target: EntityId? = null,
) : Entity {
    init {
        require(bouncesLeft >= 0) { "bouncesLeft must be non-negative: $bouncesLeft" }
        require(massScale > 0f) { "massScale must be positive: $massScale" }
    }

    override val hitbox: Hitbox get() = kind.stats.hitbox
    val density: Float get() = kind.stats.density * massScale
    override val mass: Float = kind.stats.hitbox.area * kind.stats.density * massScale
    val faction: Faction get() = kind.faction
    val isExpired: Boolean get() = age >= kind.stats.lifetime

    override fun moved(position: Vec2, velocity: Vec2) = copy(position = position, velocity = velocity)
    fun aged(dt: Float) = copy(age = age + dt)
    fun bounced() = copy(bouncesLeft = bouncesLeft - 1)
    fun homing(target: EntityId, velocity: Vec2) = copy(target = target, velocity = velocity)
}

/** Усилитель на поле. HP нет, сбить нельзя — подбирается только игроком. */
data class PowerUp(
    override val id: EntityId,
    val type: PowerUpType,
    override val position: Vec2,
    override val velocity: Vec2,
) : Entity {
    override val hitbox: Hitbox get() = Config.PowerUp.HITBOX
    override val mass: Float = Config.PowerUp.HITBOX.area * Config.PowerUp.DENSITY

    override fun moved(position: Vec2, velocity: Vec2) = copy(position = position, velocity = velocity)
}

// --- Противники и союзники ---

/** Роль противника в забеге. Босс: полоса HP, глушит обычный спаун, его смерть двигает прогрессию. */
enum class Role { MOB, BOSS }

/**
 * Вид противника — неизменяемое описание. Конкретные виды — объекты в пакете `content`
 * (данные + при необходимости свой код поведения).
 * @property id стабильное имя (бестиарий, трофеи, условия фаз «убить N таких»).
 */
interface NpcKind {
    val id: String
    val title: String
    val description: List<String>
    val role: Role
    val body: NpcBody
    val behavior: Behavior
    val loot: NpcLoot
}

/**
 * Тело противника. Нос — локальная ось +Y хитбокса; угол сущности поворачивает его против часовой стрелки.
 * @property physical true — динамическое тело: толчки сдвигают его, ИИ ведёт импульсами с ускорением не больше
 *   [maxAccel]; false — кинематическое: импульсы его не сдвигают, ИИ задаёт скорость напрямую.
 * @property rotates поворачивается ли тело в игре (ИИ задаёт угол); иначе угол постоянный — [startAngle].
 * @property zones секторы корпуса с особым уроном (нос-таран, бронированный фронт).
 */
data class NpcBody(
    val hitbox: Hitbox,
    val density: Float,
    val restitution: Float,
    val maxHealth: Float,
    val armor: Float,
    val physical: Boolean,
    val maxAccel: Float,
    val rotates: Boolean = false,
    val startAngle: Float = 0f,
    val zones: List<HitZone> = emptyList(),
) {
    init {
        require(density > 0f && maxHealth > 0f && armor >= 0f && maxAccel > 0f) { "invalid npc body: $this" }
        require(restitution in 0f..1f) { "restitution out of [0; 1]: $restitution" }
    }
}

/**
 * Сектор корпуса: удары, пришедшие не дальше [halfAngle] рад от направления [center] (0 — нос, π — корма),
 * получают урон ×[takenScale], а тот, в кого врезались этим сектором, — ×[dealtScale].
 */
data class HitZone(val halfAngle: Float, val center: Float = 0f, val takenScale: Float = 1f, val dealtScale: Float = 1f) {
    init {
        require(halfAngle in 0f..PI.toFloat() && takenScale >= 0f && dealtScale >= 0f) { "invalid hit zone: $this" }
    }
}

/** Деньги — случайно из [money]; усилитель — с шансом [powerUpChance]; [trophy] — постоянный предмет (боссы). */
data class NpcLoot(val money: IntRange, val powerUpChance: Float = 0f, val trophy: Boolean = false) {
    init {
        require(money.first >= 0 && powerUpChance in 0f..1f) { "invalid loot: $this" }
    }
}

/**
 * Влёт на поле: противник летит к [target] со скоростью [speed], не слушая свой ИИ, и проходит сквозь боковые стены.
 * Долетел — точка входа снимается, дальше работает поведение вида (засада из-за бокового края).
 */
data class Entry(val target: Vec2, val speed: Float) {
    init {
        require(speed > 0f) { "entry speed must be positive: $speed" }
    }
}

/**
 * Память ИИ между шагами. [phase] — номер фазы поведения, [phaseTime] — секунд в ней,
 * [kills] — убитые с начала фазы противники по id вида (для перехода «убить N»).
 */
data class Brain(
    val phase: Int = 0,
    val phaseTime: Float = 0f,
    val kills: Map<String, Int> = emptyMap(),
    val move: MoveState = MoveState(),
    val attack: AttackState = AttackState(),
) {
    fun recordKill(kindId: String) = copy(kills = kills + (kindId to (kills[kindId] ?: 0) + 1))
}

// --- Саммоны ---

/**
 * Как призывать саммонов.
 * @property count сколько за один призыв.
 * @property maxAlive сколько саммонов по этому описанию может быть живо от одного призывателя (обязательный лимит;
 *   сверху действует общий Config.Summon.MAX_TOTAL на поле).
 * @property lifetime через сколько секунд саммон исчезает сам; null — живёт, пока не убьют.
 * @property diesWithOwner исчезает вместе с призывателем.
 * @property keepsBetweenLevels переживает переход на следующий уровень (имеет смысл для союзников).
 * @property spread радиус вокруг призывателя, в котором саммоны появляются.
 */
data class SummonSpec(
    val kind: NpcKind,
    val count: Int = 1,
    val maxAlive: Int = Config.Summon.DEFAULT_MAX_ALIVE,
    val lifetime: Float? = null,
    val diesWithOwner: Boolean = false,
    val keepsBetweenLevels: Boolean = false,
    val spread: Float = Config.Summon.SPREAD,
) {
    init {
        require(count >= 1 && maxAlive >= 1 && (lifetime == null || lifetime > 0f) && spread >= 0f) { "invalid summon: $this" }
    }
}

/** Связь саммона с призывателем: кто призвал, по какому описанию и сколько секунд он уже живёт. */
data class SummonTie(val owner: EntityId, val spec: SummonSpec, val age: Float = 0f) {
    val isExpired: Boolean get() = spec.lifetime?.let { age >= it } ?: false
}

// --- Создание сущностей ---

fun newPlayer(id: EntityId, ship: ShipType = ShipType.Rebel) = Player(
    id = id,
    ship = ship,
    position = Vec2(Config.Physics.WORLD_WIDTH / 2, Config.Player.START_Y),
    velocity = Vec2.ZERO,
    health = Health.full(ship.stats.maxHealth),
)

fun newMeteor(id: EntityId, size: MeteorSize, position: Vec2, velocity: Vec2, entering: Boolean = false) =
    Meteor(id, size, position, velocity, Health.full(size.stats.maxHealth), entering)

fun newNpc(id: EntityId, kind: NpcKind, position: Vec2, velocity: Vec2 = Vec2.ZERO, entry: Entry? = null) =
    Npc(id, kind, position, velocity, Health.full(kind.body.maxHealth), entry = entry)

/** Саммон: сторона призывателя, связь с ним для лимитов, времени жизни и исчезновения вместе с ним. */
fun newSummon(id: EntityId, spec: SummonSpec, owner: EntityId, faction: Faction, position: Vec2) =
    Npc(id, spec.kind, position, Vec2.ZERO, Health.full(spec.kind.body.maxHealth), faction = faction, summon = SummonTie(owner, spec))

fun newProjectile(id: EntityId, kind: ProjectileKind, position: Vec2, velocity: Vec2, massScale: Float = 1f) =
    Projectile(id, kind, position, velocity, massScale = massScale)

fun newPowerUp(id: EntityId, type: PowerUpType, position: Vec2) =
    PowerUp(id, type, position, Vec2(0f, -Config.PowerUp.FALL_SPEED))
