package com.spaceace.game.npc

import com.spaceace.game.hazard.BeamShot
import com.spaceace.game.hazard.BeamSpec
import com.spaceace.game.hazard.Zone
import com.spaceace.game.hazard.ZoneSpec
import com.spaceace.game.model.Entity
import com.spaceace.game.model.Field
import com.spaceace.game.model.MeteorSize
import com.spaceace.game.model.Npc
import com.spaceace.game.model.ProjectileKind
import com.spaceace.game.model.SummonSpec
import com.spaceace.game.model.Vec2
import kotlin.math.PI
import kotlin.random.Random

/*
 * Поведение противника — данные: фазы, в каждой — движение, план атак, условие перехода и окружение.
 * Готовых блоков хватает для большинства противников; уникальное — через Custom (свой код в файле противника).
 * Исполняет всё это движок в npc/ (чистые функции).
 */

/** Сценарий: фазы по порядку; из последней перехода нет. */
data class Behavior(val phases: List<BehaviorPhase>) {
    constructor(single: BehaviorPhase) : this(listOf(single))

    init {
        require(phases.isNotEmpty()) { "behavior needs at least one phase" }
    }
}

/**
 * @property exit когда перейти к следующей фазе; null — никогда.
 * @property environment что происходит на поле, пока противник в этой фазе (только для боссов).
 */
data class BehaviorPhase(
    val movement: Movement,
    val attacks: AttackPlan = AttackPlan.None,
    val exit: Transition? = null,
    val environment: Environment = Environment.Calm,
)

/**
 * Что противник видит вокруг. [target] — кого атакует: у врагов — корабль игрока, у союзников — ближайший враг
 * (нет врагов — ближайший метеорит). [owner] — призыватель саммона (союзнику — корабль игрока).
 */
data class Sight(val target: Entity?, val field: Field, val owner: Entity? = null)

/** Вход своего кода (движение, атаки, переходы): снимок противника, обзор, шаг и случайность. */
data class NpcInput(val npc: Npc, val sight: Sight, val dt: Float, val random: Random)

/** Своё состояние для Custom-кода: свой data class, реализующий этот интерфейс. */
interface Memory

// --- Движение ---

/** [angle] — нужный угол носа (только у вращающихся тел); null — не менять. */
data class MoveResult(val velocity: Vec2, val state: MoveState, val angle: Float? = null)

/** Состояние движения: вход на позицию, направление патруля, своя память Custom-движения. */
data class MoveState(val entered: Boolean = false, val direction: Float = 1f, val memory: Memory? = null)

sealed interface Movement {
    /** Летит вниз с постоянной скоростью. */
    data class Descend(val speed: Float) : Movement

    /** Подлетает к точке ([hoverX], [hoverY]) со скоростью [entrySpeed] и зависает на ней. */
    data class Hover(val entrySpeed: Float, val hoverY: Float, val hoverX: Float) : Movement

    /**
     * Спускается на высоту [hoverY] со скоростью [entrySpeed], затем ходит по X от края до края со скоростью [speed],
     * не подходя к стенам ближе [edgeMargin]; [hoverGain] (1/с) — как сильно возвращается на высоту патруля.
     */
    data class Patrol(val hoverY: Float, val speed: Float, val entrySpeed: Float, val edgeMargin: Float, val hoverGain: Float) : Movement

    /**
     * Держится над игроком на [distance] по Y и выравнивается с ним по X (скорость до [speedX]); вверх не летает —
     * подходит снижением со скоростью [descendSpeed] и зависает. Игрок оказался выше — уходит вниз со скоростью [leaveSpeed].
     */
    data class Stalk(val distance: Float, val speedX: Float, val descendSpeed: Float, val leaveSpeed: Float) : Movement

    /**
     * Держится рядом с призывателем: у каждого саммона своё место на кольце радиуса [radius] на [height] выше
     * призывателя; скорость до [speed]. Призывателя нет — стоит на месте.
     */
    data class Escort(val radius: Float, val height: Float, val speed: Float) : Movement

    /** Своё движение. */
    class Custom(val step: (NpcInput) -> MoveResult) : Movement
}

// --- Атаки ---

/** Куда направлен выстрел. */
sealed interface Aim {
    /**
     * В цель (у врагов — в игрока, у союзников — в ближайшего врага); если она дальше [halfAngle] рад от оси [axis] —
     * выстрела нет (вне сектора обстрела).
     */
    data class AtTarget(val halfAngle: Float = PI.toFloat(), val axis: Vec2 = DOWN) : Aim

    /** В заданном направлении (в осях поля). */
    data class Fixed(val direction: Vec2) : Aim

    companion object {
        val DOWN = Vec2(0f, -1f)
    }
}

/** Что делает шаг плана атак: очередь выстрелов или призыв. */
sealed interface Action

/** Призыв саммонов (лимиты — в [SummonSpec]). */
data class Summon(val spec: SummonSpec) : Action

/**
 * Лазер: луч по [aim]; направление фиксируется в момент выстрела. Сначала [BeamSpec.warmup] с предупреждения
 * без урона, затем [duration] с урона; шаг плана ждёт всё это и свою паузу.
 */
data class Laser(val spec: BeamSpec, val duration: Float, val aim: Aim = Aim.AtTarget()) : Action {
    init {
        require(duration > 0f) { "laser duration must be positive: $this" }
    }
}

/** Опасная зона: где сейчас цель ([ZoneAt.TARGET]) или под самим противником ([ZoneAt.SELF]). */
data class Hazard(val spec: ZoneSpec, val at: ZoneAt = ZoneAt.TARGET) : Action

enum class ZoneAt { TARGET, SELF }

/** Горящий луч противника: направление, сколько он уже горит (с предупреждением) и сколько должен гореть. */
data class ActiveBeam(val spec: BeamSpec, val direction: Vec2, val duration: Float, val age: Float = 0f) {
    val isOver: Boolean get() = age >= spec.warmup + duration
    val live: Boolean get() = age >= spec.warmup
}

/**
 * Очередь: [count] выстрелов через [interval] с; каждый выстрел — веер из [fan] снарядов с шагом [fanStep] рад,
 * каждый снаряд отклонён случайно на ±[spread] рад. [muzzle] — точка вылета в осях корпуса; ZERO — у края корпуса
 * в сторону выстрела.
 */
data class Burst(
    val projectile: ProjectileKind,
    val count: Int,
    val interval: Float,
    val speed: Float,
    val aim: Aim,
    val spread: Float = 0f,
    val fan: Int = 1,
    val fanStep: Float = 0f,
    val muzzle: Vec2 = Vec2.ZERO,
) : Action {
    init {
        require(count >= 1 && interval >= 0f && speed > 0f && spread >= 0f && fan >= 1) { "invalid burst: $this" }
    }
}

/**
 * Шаг плана: действие (очередь или призыв), повторённое случайное число раз из [repeats], с паузой [pause] с после
 * каждого; [weight] — для Pick.RANDOM.
 */
data class AttackStep(val action: Action, val repeats: IntRange = 1..1, val pause: Float, val weight: Float = 1f) {
    init {
        require(repeats.first >= 1 && pause >= 0f && weight >= 0f) { "invalid attack step: $this" }
    }
}

enum class Pick { IN_ORDER, RANDOM }

/** Выстрел противника: снаряд, точка вылета в осях поля, скорость. */
data class NpcShot(val kind: ProjectileKind, val position: Vec2, val velocity: Vec2)

/**
 * [summons] — кого призвать в этот шаг (лимиты проверит NpcSystem); [beams] — горящие в этот шаг лучи;
 * [zones] — новые опасные зоны.
 */
data class AttackResult(
    val shots: List<NpcShot>,
    val state: AttackState,
    val summons: List<SummonSpec> = emptyList(),
    val beams: List<BeamShot> = emptyList(),
    val zones: List<Zone> = emptyList(),
)

/**
 * Состояние атак: текущий шаг плана, сколько очередей шага и выстрелов очереди осталось, задержка до следующего действия,
 * своя память Custom-атаки.
 */
data class AttackState(
    val step: Int = -1,
    val repeatsLeft: Int = 0,
    val shotsLeft: Int = 0,
    val cooldown: Float = 0f,
    val memory: Memory? = null,
    val beam: ActiveBeam? = null,
)

sealed interface AttackPlan {
    data object None : AttackPlan

    /** Шаги друг за другом (IN_ORDER) или случайно по весам (RANDOM); первый — через [startDelay] с. */
    data class Cycle(val steps: List<AttackStep>, val pick: Pick = Pick.IN_ORDER, val startDelay: Float = 0f) : AttackPlan {
        init {
            require(steps.isNotEmpty() && startDelay >= 0f) { "invalid attack cycle: $this" }
        }
    }

    /** Свои атаки. */
    class Custom(val step: (NpcInput) -> AttackResult) : AttackPlan
}

// --- Переходы между фазами ---

sealed interface Transition {
    /** HP упало ниже доли [fraction]. */
    data class HealthBelow(val fraction: Float) : Transition

    /** Фаза длится [seconds] с. */
    data class After(val seconds: Float) : Transition

    /** С начала фазы убито [count] противников вида [kindId] (null — любых). */
    data class Kills(val count: Int, val kindId: String? = null) : Transition

    /** Любое из условий. */
    data class AnyOf(val options: List<Transition>) : Transition

    /** Своё условие. */
    class Custom(val check: (NpcInput) -> Boolean) : Transition
}

// --- Окружение фазы ---

sealed interface Environment {
    /** Ничего особенного: пока жив босс, метеориты не появляются. */
    data object Calm : Environment

    /** Метеориты вылетают из-за боковых краёв поля вниз по диагонали. */
    data class SideMeteors(val settings: SideMeteorSettings) : Environment
}

/**
 * @property minAngle, maxAngle наклон полёта ниже горизонтали, рад.
 * @property minY, maxY высота вылета, доли высоты поля.
 */
data class SideMeteorSettings(
    val interval: Float,
    val intervalJitter: Float,
    val minSpeed: Float,
    val maxSpeed: Float,
    val minAngle: Float,
    val maxAngle: Float,
    val minY: Float,
    val maxY: Float,
    val sizeWeights: Map<MeteorSize, Float>,
) {
    init {
        require(interval > 0f && intervalJitter in 0f..1f && minSpeed in 0f..maxSpeed) { "invalid side meteors: $this" }
        require(minAngle in 0f..maxAngle && minY in 0f..maxY && maxY <= 1f) { "invalid side meteors: $this" }
        require(sizeWeights.values.all { it >= 0f } && sizeWeights.values.sum() > 0f) { "invalid size weights: $sizeWeights" }
    }
}
