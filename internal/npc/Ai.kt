package com.spaceace.game.npc

import com.spaceace.game.Config
import com.spaceace.game.hazard.BeamShot
import com.spaceace.game.hazard.Zone
import com.spaceace.game.logic.weightedPick
import com.spaceace.game.model.Brain
import com.spaceace.game.model.Entity
import com.spaceace.game.model.Field
import com.spaceace.game.model.Meteor
import com.spaceace.game.model.Npc
import com.spaceace.game.model.ProjectileKind
import com.spaceace.game.model.SummonSpec
import com.spaceace.game.model.TWO_PI
import com.spaceace.game.model.Vec2
import com.spaceace.game.model.angleBetween
import com.spaceace.game.model.rotatedBy
import com.spaceace.game.weapon.isOnField
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
// --- Шаг ИИ ---

/**
 * Решение противника на один шаг. [velocity] — желаемая скорость (как её добиться, решает физичность тела);
 * [angle] — желаемый угол носа (только у вращающихся тел).
 */
data class Orders(
    val brain: Brain,
    val velocity: Vec2,
    val angle: Float?,
    val shots: List<NpcShot>,
    val summons: List<SummonSpec> = emptyList(),
    val beams: List<BeamShot> = emptyList(),
    val zones: List<Zone> = emptyList(),
)

/**
 * Шаг ИИ по данным поведения вида: сначала проверяется выход из текущей фазы, затем движение и атаки фазы.
 * Чистая функция: всё состояние — в [Npc.brain], случайность — [random].
 */
fun think(npc: Npc, sight: Sight, dt: Float, random: Random): Orders {
    val phases = npc.kind.behavior.phases
    var brain = npc.brain.copy(phase = npc.brain.phase.coerceIn(phases.indices), phaseTime = npc.brain.phaseTime + dt)
    val exit = phases[brain.phase].exit
    if (exit != null && brain.phase < phases.lastIndex && isMet(exit, NpcInput(npc.withBrain(brain), sight, dt, random))) {
        brain = brain.copy(phase = brain.phase + 1, phaseTime = 0f, kills = emptyMap(), attack = AttackState())
    }
    val phase = phases[brain.phase]
    val input = NpcInput(npc.withBrain(brain), sight, dt, random)
    val move = move(phase.movement, input)
    val attack = attack(phase.attacks, input)
    return Orders(brain.copy(move = move.state, attack = attack.state), move.velocity, move.angle, attack.shots, attack.summons, attack.beams, attack.zones)
}

/** Текущая фаза поведения противника. */
fun Npc.currentPhase() = kind.behavior.phases[brain.phase.coerceIn(kind.behavior.phases.indices)]

// --- Переходы между фазами ---

/** Выполнено ли условие перехода к следующей фазе. */
fun isMet(transition: Transition, input: NpcInput): Boolean {
    val npc = input.npc
    return when (transition) {
        is Transition.HealthBelow -> npc.health.fraction < transition.fraction
        is Transition.After -> npc.brain.phaseTime >= transition.seconds
        is Transition.Kills -> {
            val kills = npc.brain.kills
            val count = transition.kindId?.let { kills[it] ?: 0 } ?: kills.values.sum()
            count >= transition.count
        }
        is Transition.AnyOf -> transition.options.any { isMet(it, input) }
        is Transition.Custom -> transition.check(input)
    }
}

// --- Движение ---

/** Движение фазы на один шаг. */
fun move(movement: Movement, input: NpcInput): MoveResult = when (movement) {
    is Movement.Descend -> MoveResult(Vec2(0f, -movement.speed), input.npc.brain.move)
    is Movement.Hover -> hover(movement, input)
    is Movement.Patrol -> patrol(movement, input)
    is Movement.Stalk -> stalk(movement, input)
    is Movement.Escort -> escort(movement, input)
    is Movement.Custom -> movement.step(input)
}

private fun patrol(patrol: Movement.Patrol, input: NpcInput): MoveResult {
    val npc = input.npc
    var state = npc.brain.move
    if (!state.entered) {
        if (npc.position.y > patrol.hoverY) return MoveResult(Vec2(0f, -patrol.entrySpeed), state)
        state = state.copy(entered = true)
    }
    var direction = state.direction
    val x = npc.position.x
    val halfWidth = npc.hitbox.halfWidth
    if (x - halfWidth <= patrol.edgeMargin && direction < 0f) direction = 1f
    if (x + halfWidth >= input.sight.field.width - patrol.edgeMargin && direction > 0f) direction = -1f
    val velocity = Vec2(direction * patrol.speed, (patrol.hoverY - npc.position.y) * patrol.hoverGain)
    return MoveResult(velocity, state.copy(direction = direction))
}

/**
 * Выравнивание по X и подход сверху пропорциональны отставанию (за ~1/STALK_GAIN с), поэтому у цели нет дрожи.
 * Высота держится не выше поля, иначе при высоко поднявшемся игроке противник завис бы за верхним краем.
 */
private fun stalk(stalk: Movement.Stalk, input: NpcInput): MoveResult {
    val npc = input.npc
    val state = npc.brain.move
    val player = input.sight.target ?: return MoveResult(Vec2(0f, -stalk.descendSpeed), state)
    if (player.position.y >= npc.position.y) return MoveResult(Vec2(0f, -stalk.leaveSpeed), state)

    val vx = ((player.position.x - npc.position.x) * Config.Enemy.STALK_GAIN).coerceIn(-stalk.speedX, stalk.speedX)
    val targetY = min(player.position.y + stalk.distance, input.sight.field.height - npc.hitbox.top)
    val above = npc.position.y - targetY
    val vy = if (above > 0f) -min(stalk.descendSpeed, above * Config.Enemy.STALK_GAIN) else 0f
    return MoveResult(Vec2(vx, vy), state)
}

/** Летит к точке зависания не быстрее [Movement.Hover.entrySpeed]; у точки скорость гаснет пропорционально отставанию. */
private fun hover(hover: Movement.Hover, input: NpcInput): MoveResult {
    val toSpot = Vec2(hover.hoverX, hover.hoverY) - input.npc.position
    val speed = (toSpot.length * Config.Enemy.STALK_GAIN).coerceAtMost(hover.entrySpeed)
    return MoveResult(toSpot.normalized() * speed, input.npc.brain.move)
}

/**
 * Место саммона на кольце вокруг точки над призывателем берётся по номеру сущности, поэтому у соседей оно разное
 * и не меняется со временем. Скорость пропорциональна отставанию (за ~1/STALK_GAIN с) и не больше [Movement.Escort.speed].
 */
private fun escort(escort: Movement.Escort, input: NpcInput): MoveResult {
    val npc = input.npc
    val state = npc.brain.move
    val owner = input.sight.owner ?: return MoveResult(Vec2.ZERO, state)
    val slot = npc.id.value.mod(Config.Summon.ESCORT_SLOTS)
    val angle = TWO_PI * slot / Config.Summon.ESCORT_SLOTS
    val spot = owner.position + Vec2(cos(angle) * escort.radius, escort.height + sin(angle) * escort.radius * Config.Summon.ESCORT_FLATTEN)
    val toSpot = spot - npc.position
    val speed = (toSpot.length * Config.Enemy.STALK_GAIN).coerceAtMost(escort.speed)
    return MoveResult(toSpot.normalized() * speed, state)
}

// --- Атаки ---

/**
 * Атаки фазы на один шаг. Противник за пределами поля (ещё влетает сверху) не стреляет. Горящий луч доигрывается
 * при любом плане: его ведёт состояние атак, а не текущий шаг.
 */
fun attack(plan: AttackPlan, input: NpcInput): AttackResult {
    val (beams, burning) = burnBeam(input)
    val next = input.copy(npc = input.npc.withBrain(input.npc.brain.copy(attack = input.npc.brain.attack.copy(beam = burning))))
    val result = when (plan) {
        AttackPlan.None -> AttackResult(emptyList(), next.npc.brain.attack)
        is AttackPlan.Cycle -> cycle(plan, next)
        is AttackPlan.Custom -> if (isOnField(next)) plan.step(next) else AttackResult(emptyList(), next.npc.brain.attack)
    }
    return result.copy(beams = beams + result.beams)
}

/** Луч горит от корпуса в зафиксированном направлении; предупреждение — без урона; время вышло — луча нет. */
private fun burnBeam(input: NpcInput): Pair<List<BeamShot>, ActiveBeam?> {
    val npc = input.npc
    val beam = npc.brain.attack.beam ?: return emptyList<BeamShot>() to null
    val aged = beam.copy(age = beam.age + input.dt)
    if (aged.isOver) return emptyList<BeamShot>() to null
    val shot = BeamShot(npc.id, npc.faction, npc.position + beam.direction * npc.radius, beam.direction, beam.spec, aged.live)
    return listOf(shot) to aged
}

/**
 * Очередь идёт выстрел за выстрелом через interval; после очереди — пауза шага; когда очереди шага кончились,
 * берётся следующий шаг. Цель вне сектора — выстрел откладывается на AIM_RETRY. Призыв — одно действие, затем пауза.
 */
private fun cycle(plan: AttackPlan.Cycle, input: NpcInput): AttackResult {
    var state = input.npc.brain.attack
    if (state.step < 0) state = nextStep(plan, state, input.random).copy(cooldown = plan.startDelay)
    val cooldown = state.cooldown - input.dt
    if (cooldown > 0f || !isOnField(input)) return AttackResult(emptyList(), state.copy(cooldown = cooldown.coerceAtLeast(0f)))

    if (state.shotsLeft == 0 && state.repeatsLeft == 0) state = nextStep(plan, state, input.random)
    val step = plan.steps[state.step]
    when (val action = step.action) {
        is Laser -> {
            val direction = aimDirection(action.aim, input.npc, input)
                ?: return AttackResult(emptyList(), state.copy(cooldown = Config.Enemy.AIM_RETRY))
            val next = state.copy(
                shotsLeft = 0,
                repeatsLeft = state.repeatsLeft - 1,
                cooldown = action.spec.warmup + action.duration + step.pause,
                beam = ActiveBeam(action.spec, direction, action.duration),
            )
            return AttackResult(emptyList(), next)
        }
        is Hazard -> {
            val center = when (action.at) {
                ZoneAt.TARGET -> input.sight.target?.position
                ZoneAt.SELF -> input.npc.position
            } ?: return AttackResult(emptyList(), state.copy(cooldown = Config.Enemy.AIM_RETRY))
            val next = state.copy(shotsLeft = 0, repeatsLeft = state.repeatsLeft - 1, cooldown = step.pause)
            return AttackResult(emptyList(), next, zones = listOf(Zone(center, action.spec, input.npc.faction)))
        }
        is Summon -> {
            val next = state.copy(shotsLeft = 0, repeatsLeft = state.repeatsLeft - 1, cooldown = step.pause)
            return AttackResult(emptyList(), next, summons = listOf(action.spec))
        }
        is Burst -> {
            val shots = volley(action, input) ?: return AttackResult(emptyList(), state.copy(cooldown = Config.Enemy.AIM_RETRY))
            val starting = state.shotsLeft == 0
            val shotsLeft = (if (starting) action.count else state.shotsLeft) - 1
            val next = state.copy(
                shotsLeft = shotsLeft,
                repeatsLeft = if (starting) state.repeatsLeft - 1 else state.repeatsLeft,
                cooldown = if (shotsLeft > 0) action.interval else step.pause,
            )
            return AttackResult(shots, next)
        }
    }
}

private fun nextStep(plan: AttackPlan.Cycle, state: AttackState, random: Random): AttackState {
    val index = when (plan.pick) {
        Pick.IN_ORDER -> (state.step + 1).mod(plan.steps.size)
        Pick.RANDOM -> weightedPick(plan.steps.indices.associateWith { plan.steps[it].weight }, random)
    }
    val repeats = plan.steps[index].repeats
    return state.copy(step = index, repeatsLeft = random.nextInt(repeats.first, repeats.last + 1), shotsLeft = 0)
}

/** Один выстрел очереди (веер с разбросом); null — цель вне сектора обстрела или её нет. */
fun volley(burst: Burst, input: NpcInput): List<NpcShot>? {
    val npc = input.npc
    val base = aimDirection(burst.aim, npc, input) ?: return null
    return List(burst.fan) { i ->
        val offset = (i - (burst.fan - 1) / 2f) * burst.fanStep + (input.random.nextFloat() * 2f - 1f) * burst.spread
        val direction = base.rotatedBy(offset)
        val position = if (burst.muzzle == Vec2.ZERO) muzzleToward(npc, burst.projectile, direction) else npc.position + burst.muzzle.rotatedBy(npc.angle)
        NpcShot(burst.projectile, position, direction * burst.speed)
    }
}

/** Прицельный выстрел из своего кода: снаряд вылетает у края корпуса в сторону [direction]. */
fun aimedShot(npc: Npc, kind: ProjectileKind, direction: Vec2, speed: Float): NpcShot {
    val unit = direction.normalized()
    return NpcShot(kind, muzzleToward(npc, kind, unit), unit * speed)
}

private fun aimDirection(aim: Aim, npc: Npc, input: NpcInput): Vec2? = when (aim) {
    is Aim.Fixed -> aim.direction.normalized()
    is Aim.AtTarget -> {
        val toTarget = input.sight.target?.let { (it.position - npc.position).normalized() }
        toTarget?.takeIf { it != Vec2.ZERO && angleBetween(it, aim.axis) <= aim.halfAngle }
    }
}

/** Снаряд появляется за описанной окружностью корпуса, чтобы не родиться внутри него. */
private fun muzzleToward(npc: Npc, kind: ProjectileKind, direction: Vec2): Vec2 =
    npc.position + direction * (npc.radius + kind.stats.radius)

private fun isOnField(input: NpcInput): Boolean = input.npc.position.y in 0f..input.sight.field.height

// --- Влёт из засады ---

/** Скорость влёта к точке входа; null — точки входа нет или противник уже долетел. */
fun entryVelocity(npc: Npc, tolerance: Float = Config.Enemy.ENTRY_TOLERANCE): Vec2? {
    val entry = npc.entry ?: return null
    val toTarget = entry.target - npc.position
    if (toTarget.length <= tolerance) return null
    return toTarget.normalized() * entry.speed
}

// --- Саммоны ---

/**
 * Сколько саммонов можно призвать сейчас: не больше [SummonSpec.count], не больше остатка лимита этого призыва
 * у призывателя ([ownerAlive] уже живы) и не больше остатка общего потолка на поле ([totalAlive] саммонов всего).
 */
fun summonRoom(spec: SummonSpec, ownerAlive: Int, totalAlive: Int, maxTotal: Int = Config.Summon.MAX_TOTAL): Int =
    minOf(spec.count, spec.maxAlive - ownerAlive, maxTotal - totalAlive).coerceAtLeast(0)

/** Места появления: равномерно по кругу радиуса [spread] вокруг [center], со случайным поворотом. */
fun summonPositions(center: Vec2, count: Int, spread: Float, random: Random): List<Vec2> {
    val turn = random.nextFloat() * TWO_PI
    return List(count) { i ->
        val angle = turn + TWO_PI * i / count
        center + Vec2(cos(angle), sin(angle)) * spread
    }
}

/** Саммону пора исчезнуть: истёк срок жизни или призыватель погиб, а саммон должен уйти вместе с ним. */
fun shouldVanish(summon: Npc, ownerAlive: Boolean): Boolean {
    val tie = summon.summon ?: return false
    return tie.isExpired || (tie.spec.diesWithOwner && !ownerAlive)
}

/** Цель союзника: ближайший враг на поле, а если врагов нет — ближайший метеорит на поле. */
fun allyTarget(from: Vec2, entities: Collection<Entity>, field: Field): Entity? {
    val visible = entities.filter { isOnField(it.position, field) }
    val foes = visible.filter { it is Npc && !it.isAlly }
    val pool = foes.ifEmpty { visible.filterIsInstance<Meteor>() }
    return pool.minByOrNull { (it.position - from).lengthSquared }
}
