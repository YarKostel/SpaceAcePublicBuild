package com.spaceace.game.weapon

import com.spaceace.game.Config
import com.spaceace.game.hazard.BeamSpec
import com.spaceace.game.model.ProjectileKind
import com.spaceace.game.model.Vec2
import com.spaceace.game.model.WeaponType
import kotlin.random.Random

// --- Режимы огня и спуск ---

/**
 * Режим огня оружия. Логика спуска — `Trigger.update` в Ballistics.kt.
 * Любой режим может стрелять серией: один выстрел = [salvos] залпов через [salvoInterval] секунд.
 */
sealed interface FireMode {
    val salvos: Int
    val salvoInterval: Float

    /** Стреляет, пока спуск зажат, не чаще раза в [interval] секунд. */
    data class Auto(
        val interval: Float,
        override val salvos: Int = 1,
        override val salvoInterval: Float = 0f,
    ) : FireMode {
        init {
            require(salvos >= 1 && salvoInterval >= 0f) { "invalid salvo settings: $this" }
        }
    }

    /** Один выстрел на нажатие, не чаще раза в [interval] секунд. */
    data class SemiAuto(
        val interval: Float,
        override val salvos: Int = 1,
        override val salvoInterval: Float = 0f,
    ) : FireMode {
        init {
            require(salvos >= 1 && salvoInterval >= 0f) { "invalid salvo settings: $this" }
        }
    }

    /**
     * Непрерывный луч, пока спуск зажат. Нагрев растёт за [heatTime] с огня до полного; перегрелся — луч гаснет,
     * пока не остынет полностью (за [coolTime] с от полного).
     */
    data class Beam(
        val heatTime: Float,
        val coolTime: Float,
        override val salvos: Int = 1,
        override val salvoInterval: Float = 0f,
    ) : FireMode {
        init {
            require(heatTime > 0f && coolTime > 0f && salvos == 1) { "invalid beam mode: $this" }
        }
    }

    /** Заряд копится, пока спуск зажат; выстрел при отпускании, если заряд не меньше [minCharge]. */
    data class Charge(
        val minCharge: Float,
        val maxCharge: Float,
        val cooldown: Float,
        override val salvos: Int = 1,
        override val salvoInterval: Float = 0f,
    ) : FireMode {
        init {
            require(minCharge in 0f..maxCharge && maxCharge > 0f) { "invalid charge mode: $this" }
            require(salvos >= 1 && salvoInterval >= 0f) { "invalid salvo settings: $this" }
        }
    }
}

/**
 * Состояние спуска: остаток перезарядки, накопленный заряд, был ли спуск зажат в прошлом шаге
 * и сколько залпов текущей серии ещё впереди.
 */
data class Trigger(
    val cooldown: Float = 0f,
    val charge: Float = 0f,
    val wasHeld: Boolean = false,
    val salvosLeft: Int = 0,
    val salvoTimer: Float = 0f,
    /** Нагрев лучевого оружия, 0..1; [overheated] — перегрет и ждёт полного остывания. */
    val heat: Float = 0f,
    val overheated: Boolean = false,
)

/** [power] ∈ (0; 1] — сила выстрела, null — выстрела нет. */
data class TriggerResult(val trigger: Trigger, val power: Float?)

// --- Оружие ---

/** Скорость снаряда — абсолютная, в м/с. */
data class Shot(val kind: ProjectileKind, val velocity: Vec2)

/** Откуда стреляем и где цели (центры метеоритов, врагов и боссов). */
data class Aim(val muzzle: Vec2, val targets: List<Vec2>)

/**
 * Оружие — неизменяемое описание: режим огня и что вылетает при выстреле. Состояние спуска — в [Trigger],
 * вычисления — чистые функции в Ballistics.kt: общие в начале, особые — по разделу на оружие (Гаусс, рикошет,
 * ИК ракеты, зенитка); поведение снарядов в полёте применяет weapon/ProjectileSystem.
 * Новое оружие: значение в [WeaponType], data object здесь и ветка в [of] — компилятор проверит полноту.
 */
sealed interface Weapon {
    val type: WeaponType
    val mode: FireMode

    /** Снаряды одного выстрела; [power] ∈ (0; 1] — доля заряда (для Auto/SemiAuto всегда 1). */
    fun shots(power: Float, aim: Aim, random: Random): List<Shot>

    /** Луч вместо снарядов (лазер): горит в каждом шаге, пока спуск даёт выстрел; null — оружие стреляет снарядами. */
    val beam: BeamSpec? get() = null

    data object MachineGun : Weapon {
        private val cfg = Config.Weapons.MachineGun
        override val type = WeaponType.MACHINE_GUN
        override val mode: FireMode = FireMode.Auto(cfg.INTERVAL)

        override fun shots(power: Float, aim: Aim, random: Random): List<Shot> =
            listOf(Shot(ProjectileKind.BULLET, spreadDirection(degreesToRadians(cfg.SPREAD_DEG), random) * cfg.SPEED))
    }

    /** Скорострельнее пулемёта, разброс больше, снаряд тяжелее, но медленнее. */
    data object Gatling : Weapon {
        private val cfg = Config.Weapons.Gatling
        override val type = WeaponType.GATLING
        override val mode: FireMode = FireMode.Auto(cfg.INTERVAL)

        override fun shots(power: Float, aim: Aim, random: Random): List<Shot> =
            listOf(Shot(ProjectileKind.GATLING_ROUND, spreadDirection(degreesToRadians(cfg.SPREAD_DEG), random) * cfg.SPEED))
    }

    /** Чем дольше заряд, тем быстрее снаряд; пробивает насквозь (Ballistics.kt, раздел «Пушка Гаусса»). */
    data object GaussCannon : Weapon {
        private val cfg = Config.Weapons.Gauss
        override val type = WeaponType.GAUSS
        override val mode: FireMode = FireMode.Charge(cfg.MIN_CHARGE, cfg.MAX_CHARGE, cfg.COOLDOWN)

        override fun shots(power: Float, aim: Aim, random: Random): List<Shot> =
            listOf(Shot(ProjectileKind.GAUSS_SLUG, STRAIGHT_UP * charged(cfg.MIN_SPEED, cfg.MAX_SPEED, power)))
    }

    /** Один шар на нажатие, наводится на ближайшую по высоте цель выше корабля. */
    data object RicochetCannon : Weapon {
        private val cfg = Config.Weapons.Ricochet
        override val type = WeaponType.RICOCHET
        override val mode: FireMode = FireMode.SemiAuto(cfg.INTERVAL)

        override fun shots(power: Float, aim: Aim, random: Random): List<Shot> {
            val direction = aimDirection(aim.muzzle, aim.targets, degreesToRadians(cfg.MAX_AIM_ANGLE_DEG))
            return listOf(Shot(ProjectileKind.RICOCHET_ORB, direction * cfg.SPEED))
        }
    }

    /** Снаряд по нажатию; через FUSE секунд разрывается веером осколков (Ballistics.kt, раздел «Зенитка»). */
    data object FlakCannon : Weapon {
        private val cfg = Config.Weapons.Flak
        override val type = WeaponType.FLAK
        override val mode: FireMode = FireMode.SemiAuto(cfg.INTERVAL)

        override fun shots(power: Float, aim: Aim, random: Random): List<Shot> =
            listOf(Shot(ProjectileKind.FLAK_SHELL, STRAIGHT_UP * cfg.SPEED))
    }

    /**
     * Зарядка, затем серия залпов веером; ракеты сами ищут ближайшую цель (Ballistics.kt, раздел «ИК ракеты»),
     * после выстрела — долгая перезарядка.
     */
    data object IrMissiles : Weapon {
        private val cfg = Config.Weapons.IrMissiles
        override val type = WeaponType.IR_MISSILES
        override val mode: FireMode =
            FireMode.Charge(cfg.CHARGE, cfg.CHARGE, cfg.COOLDOWN, salvos = cfg.SALVOS, salvoInterval = cfg.SALVO_INTERVAL)

        override fun shots(power: Float, aim: Aim, random: Random): List<Shot> {
            val step = degreesToRadians(cfg.FAN_DEG)
            return List(cfg.MISSILES_PER_SALVO) { i ->
                Shot(ProjectileKind.MISSILE, fanDirection(i, cfg.MISSILES_PER_SALVO, step) * cfg.SPEED)
            }
        }
    }

    /** Непрерывный луч, пока зажат огонь; перегрев гасит его до остывания (hazard/ — урон и препятствия). */
    data object Laser : Weapon {
        private val cfg = Config.Weapons.Laser
        override val type = WeaponType.LASER
        override val mode: FireMode = FireMode.Beam(cfg.HEAT_TIME, cfg.COOL_TIME)
        override val beam = BeamSpec(power = cfg.POWER, length = cfg.LENGTH, width = cfg.WIDTH)

        override fun shots(power: Float, aim: Aim, random: Random): List<Shot> = emptyList()
    }

    companion object {
        fun of(type: WeaponType): Weapon = when (type) {
            WeaponType.MACHINE_GUN -> MachineGun
            WeaponType.GATLING -> Gatling
            WeaponType.GAUSS -> GaussCannon
            WeaponType.RICOCHET -> RicochetCannon
            WeaponType.IR_MISSILES -> IrMissiles
            WeaponType.FLAK -> FlakCannon
            WeaponType.LASER -> Laser
        }
    }
}
