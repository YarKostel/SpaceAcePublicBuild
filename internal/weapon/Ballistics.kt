package com.spaceace.game.weapon

import com.spaceace.game.Config
import com.spaceace.game.model.Entity
import com.spaceace.game.model.Field
import com.spaceace.game.model.Meteor
import com.spaceace.game.model.Npc
import com.spaceace.game.model.Player
import com.spaceace.game.model.Projectile
import com.spaceace.game.model.ProjectileKind
import com.spaceace.game.model.ProjectileStats
import com.spaceace.game.model.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// --- Общее ---

// Общая логика оружия игрока: спуск, направления, точка вылета, масса снаряда.

val STRAIGHT_UP = Vec2(0f, 1f)

/**
 * Один шаг спуска: стреляет ли оружие сейчас и с какой силой.
 * Режимы: Auto — пока зажат, SemiAuto — по нажатию, Charge — заряд копится и уходит при отпускании.
 * Любой выстрел может быть серией из `salvos` залпов: остальные доигрываются сами, без нажатия.
 */
fun Trigger.update(mode: FireMode, held: Boolean, dt: Float): TriggerResult {
    // Отрицательный остаток переносится в следующий интервал, чтобы темп Auto не зависел от шага.
    val raw = cooldown - dt
    val ready = raw <= 0f
    val rest = raw.coerceAtLeast(0f)

    // Пока идёт серия залпов, спуск не заряжается — серия доигрывается сама.
    if (salvosLeft > 0) {
        val timer = salvoTimer - dt
        if (timer > 0f) return TriggerResult(copy(cooldown = rest, wasHeld = held, salvoTimer = timer), null)
        val next = Trigger(cooldown = rest, wasHeld = held, salvosLeft = salvosLeft - 1, salvoTimer = timer + mode.salvoInterval)
        return TriggerResult(next, FULL_POWER)
    }

    return when (mode) {
        is FireMode.Auto ->
            if (held && ready) TriggerResult(
                Trigger(
                    cooldown = (raw + mode.interval).coerceAtLeast(0f),
                    wasHeld = true,
                    salvosLeft = mode.salvos - 1,
                    salvoTimer = mode.salvoInterval,
                ),
                FULL_POWER,
            )
            else TriggerResult(Trigger(cooldown = rest, wasHeld = held), null)

        is FireMode.SemiAuto ->
            if (held && !wasHeld && ready) TriggerResult(
                Trigger(
                    cooldown = mode.interval,
                    wasHeld = true,
                    salvosLeft = mode.salvos - 1,
                    salvoTimer = mode.salvoInterval,
                ),
                FULL_POWER,
            )
            else TriggerResult(Trigger(cooldown = rest, wasHeld = held), null)

        is FireMode.Beam -> {
            val firing = held && !overheated
            val heated = (heat + if (firing) dt / mode.heatTime else -dt / mode.coolTime).coerceIn(0f, 1f)
            val locked = when {
                firing && heated >= 1f -> true
                heated <= 0f -> false
                else -> overheated
            }
            TriggerResult(copy(heat = heated, overheated = locked, wasHeld = held), if (firing) FULL_POWER else null)
        }

        is FireMode.Charge -> when {
            held && ready -> TriggerResult(Trigger(charge = (charge + dt).coerceAtMost(mode.maxCharge), wasHeld = true), null)
            held -> TriggerResult(Trigger(cooldown = rest, wasHeld = true), null)
            wasHeld && charge >= mode.minCharge && charge > 0f -> TriggerResult(
                Trigger(cooldown = mode.cooldown, salvosLeft = mode.salvos - 1, salvoTimer = mode.salvoInterval),
                charge / mode.maxCharge,
            )
            else -> TriggerResult(Trigger(cooldown = rest), null)
        }
    }
}

/** Доля заряда (у луча — нагрева) для индикатора над кораблём; 0 для режимов без заряда. */
fun Trigger.chargeFraction(mode: FireMode): Float = when (mode) {
    is FireMode.Charge -> charge / mode.maxCharge
    is FireMode.Beam -> heat
    else -> 0f
}

/** По чему стреляет оружие игрока: цели наведения рикошета и самонаводящихся ракет. */
fun isWeaponTarget(entity: Entity): Boolean = entity is Meteor || (entity is Npc && !entity.isAlly)

/** Цель уже на поле: враги, ещё не влетевшие сверху, и улетающие за край не притягивают наведение. */
fun isOnField(position: Vec2, field: Field = Config.Physics.FIELD): Boolean =
    position.x in 0f..field.width && position.y in 0f..field.height

/** Цель наведения оружия игрока, видимая на поле. */
fun isAimable(entity: Entity): Boolean = isWeaponTarget(entity) && isOnField(entity.position)

/** Точка над носом корабля, откуда вылетают снаряды. */
fun muzzleOf(player: Player): Vec2 = player.position + Vec2(0f, player.hitbox.top + Config.Player.MUZZLE_GAP)

/** Центр нового снаряда: над дулом на свой радиус, чтобы не задеть корабль. */
fun launchPosition(muzzle: Vec2, kind: ProjectileKind): Vec2 = muzzle + Vec2(0f, kind.stats.radius)

/** Множитель массы снаряда при выстреле: под «тяжёлыми снарядами» — [Config.Weapons.HEAVY_MASS_SCALE]. */
fun shotMassScale(heavyRounds: Boolean): Float = if (heavyRounds) Config.Weapons.HEAVY_MASS_SCALE else 1f

/** Единичный вектор, повёрнутый от вертикали на [angle] радиан (положительный — вправо). */
fun fromVertical(angle: Float) = Vec2(sin(angle), cos(angle))

/** Случайное направление в пределах ±[maxSpread] радиан от вертикали (разброс пулемёта, гатлинга). */
fun spreadDirection(maxSpread: Float, random: Random): Vec2 = fromVertical((random.nextFloat() * 2f - 1f) * maxSpread)

fun degreesToRadians(degrees: Float): Float = Math.toRadians(degrees.toDouble()).toFloat()

private const val FULL_POWER = 1f

// --- Пушка Гаусса ---

// Пушка Гаусса: скорость растёт с зарядом, снаряд пробивает цели насквозь.

/** Величина, растущая с силой заряда [power] ∈ [0; 1] от [min] до [max] (скорость снаряда). */
fun charged(min: Float, max: Float, power: Float): Float = min + (max - min) * power.coerceIn(0f, 1f)

/**
 * Пробивающий снаряд — сенсор Box2D, поэтому обмен импульсом при пробитии считается здесь.
 * @property momentumTransfer доля импульса снаряда, передаваемая цели.
 * @property speedRetention доля скорости, которая остаётся у снаряда после цели.
 * @property minSpeed медленнее этого снаряд застревает.
 */
data class PierceSettings(val momentumTransfer: Float, val speedRetention: Float, val minSpeed: Float) {
    init {
        require(momentumTransfer in 0f..1f && speedRetention in 0f..1f && minSpeed >= 0f) { "invalid pierce settings: $this" }
    }
}

/** [impulse] — что получает цель; [velocity] — скорость снаряда после пробития, null — снаряд застрял. */
data class PierceResult(val impulse: Vec2, val velocity: Vec2?)

fun pierce(mass: Float, velocity: Vec2, settings: PierceSettings): PierceResult {
    val slowed = velocity * settings.speedRetention
    return PierceResult(
        impulse = velocity * (mass * settings.momentumTransfer),
        velocity = slowed.takeIf { it.length >= settings.minSpeed },
    )
}

// --- Рикошет ---

// Рикошетная пушка: наведение при выстреле. Отскоки считает ImpactResolver (physics/Impacts.kt) (bouncesLeft снаряда).

/**
 * Единичное направление на цель выше дула с наименьшим расстоянием по Y среди целей в секторе ±[maxAngle] (рад)
 * от вертикали. Цели вне сектора не рассматриваются: выстрел под предельным углом в их сторону ушёл бы в стену.
 * Целей нет — строго вверх.
 */
fun aimDirection(muzzle: Vec2, targets: List<Vec2>, maxAngle: Float): Vec2 {
    val target = targets
        .filter { it.y > muzzle.y && abs(angleFromVertical(it - muzzle)) <= maxAngle }
        .minByOrNull { it.y - muzzle.y } ?: return STRAIGHT_UP
    return fromVertical(angleFromVertical(target - muzzle))
}

/** Угол вектора от вертикали, рад (положительный — вправо). */
private fun angleFromVertical(v: Vec2): Float = atan2(v.x, v.y)

// --- ИК ракеты ---

// ИК ракеты: веер при пуске и самонаведение в полёте. Самонаводится любой снаряд с homingTurnRate > 0.

/** Направление [index]-го из [count] снарядов веера с шагом [step] радиан; веер симметричен относительно вертикали. */
fun fanDirection(index: Int, count: Int, step: Float): Vec2 = fromVertical((index - (count - 1) / 2f) * step)

/**
 * Шаг самонаведения: снаряд с новой целью и повёрнутой скоростью.
 * [current] — прежняя цель, если она ещё жива. Цели вне сектора [seekHalfAngle] (рад) от курса не рассматриваются,
 * и прежняя тоже бросается, если ушла из сектора. null — снаряд не самонаводится или целей впереди нет (летит прямо).
 */
fun guided(
    missile: Projectile,
    current: Entity?,
    candidates: List<Entity>,
    dt: Float,
    seekHalfAngle: Float = degreesToRadians(Config.Weapons.IrMissiles.SEEK_HALF_ANGLE_DEG),
): Projectile? {
    val turnRate = missile.kind.stats.homingTurnRate
    if (turnRate <= 0f) return null
    val visible = { target: Entity -> inSeekCone(missile, target.position, seekHalfAngle) }
    val target = homingTarget(current?.takeIf(visible), candidates.filter(visible), missile.position) ?: return null
    return missile.homing(target.id, steerHoming(missile.velocity, target.position - missile.position, turnRate * dt))
}

/** Цель впереди по курсу снаряда: не дальше [halfAngle] радиан от направления полёта. */
fun inSeekCone(missile: Projectile, target: Vec2, halfAngle: Float): Boolean {
    val heading = missile.velocity.normalized()
    val toTarget = (target - missile.position).normalized()
    if (heading == Vec2.ZERO || toTarget == Vec2.ZERO) return true
    return acos((heading dot toTarget).coerceIn(-1f, 1f)) <= halfAngle
}

/**
 * Цель самонаводящегося снаряда в точке [from]: прежняя, если она ещё жива ([current] не null),
 * иначе ближайшая из [candidates]; null — целей нет.
 */
fun homingTarget(current: Entity?, candidates: List<Entity>, from: Vec2): Entity? =
    current ?: candidates.minByOrNull { (it.position - from).lengthSquared }

/**
 * Поворачивает скорость к цели не больше чем на [maxTurn] радиан, сохраняя модуль.
 * [toTarget] — вектор от снаряда к цели. Поворачивает в ближнюю сторону.
 */
fun steerHoming(velocity: Vec2, toTarget: Vec2, maxTurn: Float): Vec2 {
    val speed = velocity.length
    if (speed == 0f || toTarget.lengthSquared == 0f) return velocity
    val current = atan2(velocity.y, velocity.x)
    var diff = atan2(toTarget.y, toTarget.x) - current
    while (diff > PI) diff -= TWO_PI
    while (diff < -PI) diff += TWO_PI
    val heading = current + diff.coerceIn(-maxTurn, maxTurn)
    return Vec2(cos(heading), sin(heading)) * speed
}

private const val TWO_PI = (2 * PI).toFloat()

// --- Зенитка ---

// Зенитка (игрока и врагов): снаряд с ProjectileStats.split разрывается по запалу на осколки.
// Применяет разрыв weapon/ProjectileSystem.

/** Пора ли снаряду разорваться: запал отсчитывается от выстрела; снаряд без split не разрывается. */
fun isFlakReady(projectile: Projectile): Boolean =
    projectile.kind.stats.split?.let { projectile.age >= it.fuse } ?: false

/** Осколки разлетаются равномерно по кругу (со случайным поворотом) и уносят скорость снаряда. */
fun shrapnelOf(shell: Projectile, random: Random): List<Shot> {
    val split = shell.kind.stats.split ?: return emptyList()
    val turn = random.nextFloat() * FULL_TURN
    return List(split.count) { i ->
        val angle = turn + FULL_TURN * i / split.count
        Shot(split.into, shell.velocity + Vec2(cos(angle), sin(angle)) * split.speed)
    }
}

private const val FULL_TURN = (2 * PI).toFloat()

// --- Встречи снарядов ---

// Сбивание снарядов: встреча снарядов разных сторон. Считается здесь, а не в Box2D: Box2D не ищет непрерывных
// столкновений двух «пуль» между собой, и встречные снаряды за шаг проскакивали бы друг сквозь друга.

enum class Clash { NONE, FIRST_BREAKS, SECOND_BREAKS, BOTH_BREAK }

/** Исход встречи по силе сбивания: у кого больше — уцелел, при равной гибнут оба; у обоих 0 — не взаимодействуют. */
fun clash(first: ProjectileStats, second: ProjectileStats): Clash = when {
    first.breakPower == 0f && second.breakPower == 0f -> Clash.NONE
    first.breakPower > second.breakPower -> Clash.SECOND_BREAKS
    first.breakPower < second.breakPower -> Clash.FIRST_BREAKS
    else -> Clash.BOTH_BREAK
}

/**
 * Касаются ли снаряды за шаг [dt], если летят по прямой: наименьшее расстояние между центрами на отрезке шага
 * не больше суммы радиусов (так встречные снаряды не проскакивают друг сквозь друга).
 */
fun touchWithin(a: Projectile, b: Projectile, dt: Float): Boolean {
    val offset = a.position - b.position
    val relative = (a.velocity - b.velocity) * dt
    val reach = a.radius + b.radius
    val lengthSquared = relative.lengthSquared
    val t = if (lengthSquared == 0f) 0f else (-(offset dot relative) / lengthSquared).coerceIn(0f, 1f)
    return (offset + relative * t).lengthSquared <= reach * reach
}
