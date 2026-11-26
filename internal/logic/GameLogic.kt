package com.spaceace.game.logic

import com.spaceace.game.Config
import com.spaceace.game.content.Levels
import com.spaceace.game.content.PlayerSummons
import com.spaceace.game.hazard.BeamShot
import com.spaceace.game.hazard.HazardSystem
import com.spaceace.game.input.InputSource
import com.spaceace.game.input.steeringImpulse
import com.spaceace.game.input.targetVelocityX
import com.spaceace.game.input.targetVelocityY
import com.spaceace.game.model.Damageable
import com.spaceace.game.model.Faction
import com.spaceace.game.model.Field
import com.spaceace.game.model.Meteor
import com.spaceace.game.model.MeteorSize
import com.spaceace.game.model.Npc
import com.spaceace.game.model.NpcLoot
import com.spaceace.game.model.PermanentItem
import com.spaceace.game.model.Player
import com.spaceace.game.model.PowerUp
import com.spaceace.game.model.PowerUpType
import com.spaceace.game.model.Vec2
import com.spaceace.game.model.newMeteor
import com.spaceace.game.model.newNpc
import com.spaceace.game.model.newPowerUp
import com.spaceace.game.model.newProjectile
import com.spaceace.game.npc.Environment
import com.spaceace.game.npc.NpcSpawner
import com.spaceace.game.npc.NpcSystem
import com.spaceace.game.npc.SideMeteorSpawner
import com.spaceace.game.npc.currentPhase
import com.spaceace.game.npc.hasEntered
import com.spaceace.game.physics.ImpactOutcome
import com.spaceace.game.physics.PhysicsSystem
import com.spaceace.game.state.GameState
import com.spaceace.game.state.Phase
import com.spaceace.game.state.ScreenState
import com.spaceace.game.weapon.Aim
import com.spaceace.game.weapon.ProjectileSystem
import com.spaceace.game.weapon.STRAIGHT_UP
import com.spaceace.game.weapon.Weapon
import com.spaceace.game.weapon.isAimable
import com.spaceace.game.weapon.isOnField
import com.spaceace.game.weapon.launchPosition
import com.spaceace.game.weapon.muzzleOf
import com.spaceace.game.weapon.shotMassScale
import com.spaceace.game.weapon.update
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// --- Шаг игры ---

/**
 * Один фиксированный шаг игры: ввод → корабль и оружие → фаза (метеориты и противники или босс) → ИИ противников →
 * снаряды в полёте → физика → последствия.
 * Работает только на экране PLAYING.
 */
class GameLogic(
    private val state: GameState,
    private val physics: PhysicsSystem,
    private val input: InputSource,
    private val random: Random,
) {
    private val field = Config.Physics.FIELD
    private val spawner = MeteorSpawner(Config.Meteor.SPAWNER, field, random)
    private val npcSpawner = NpcSpawner(Config.Enemy.SPAWNER, field, random)
    private val sideMeteors = SideMeteorSpawner(field, random)
    private val npcs = NpcSystem(state, physics, random)
    private val projectiles = ProjectileSystem(state, physics, random)
    private val hazards = HazardSystem(state)
    /** Маяк дрона: сколько ещё ждать возрождения; 0 — дрон появится сразу (только что подобран). */
    private var beaconCooldown = 0f

    fun step(dt: Float) {
        if (state.screen != ScreenState.PLAYING) return
        state.tick(dt)
        val control = input.poll()
        state.player?.let { applyEffects(it, dt) }
        state.player?.let { player ->
            val target = Vec2(
                targetVelocityX(player.position.x, control, Config.Player.STEERING),
                targetVelocityY(player.position.y, control, Config.Player.STEERING, Config.Player.CLIMB),
            )
            steer(player, target, dt)
            fire(player, control.fire, dt)
        }
        when (val phase = state.run.phase) {
            is Phase.Level -> runLevel(phase.level, dt)
            is Phase.BossFight -> runBossFight(phase.level, dt)
            is Phase.Shop -> Unit
        }
        summonForPlayer(dt)
        npcs.step(dt)
        markSeen()
        letMeteorsIn()
        projectiles.step(dt)
        onDestroyed(hazards.step(dt))
        handle(physics.step(dt))
    }

    /** Щит обнуляет входящий урон, ремонт лечит понемногу каждый шаг. */
    private fun applyEffects(player: Player, dt: Float) {
        val effects = state.run.effects
        var updated = player.withShield(effects.isActive(PowerUpType.Shield))
        if (effects.isActive(PowerUpType.Repair) && !updated.health.isDepleted) {
            updated = updated.withHealth(updated.health.healed(Config.PowerUp.REPAIR_HEAL_PER_SECOND * dt))
        }
        if (updated != player) state.entities.replace(updated)
    }

    private fun steer(player: Player, target: Vec2, dt: Float) {
        val accel = Config.Player.STEERING.maxAccel
        val impulse = Vec2(
            steeringImpulse(player.mass, player.velocity.x, target.x, accel, dt),
            steeringImpulse(player.mass, player.velocity.y, target.y, accel, dt),
        )
        physics.applyImpulse(player.id, impulse)
    }

    private fun fire(player: Player, held: Boolean, dt: Float) {
        val weapon = Weapon.of(state.run.weapon)
        val result = state.run.trigger.update(weapon.mode, held, dt)
        state.setTrigger(result.trigger)
        val power = result.power ?: return

        val muzzle = muzzleOf(player)
        weapon.beam?.let { spec ->
            state.hazards.fire(BeamShot(player.id, Faction.PLAYER, muzzle, STRAIGHT_UP, spec, live = true))
            return
        }
        val targets = state.entities.all.filter(::isAimable).map { it.position }
        val massScale = shotMassScale(heavyRounds = state.run.effects.isActive(PowerUpType.HeavyRounds))
        for (shot in weapon.shots(power, Aim(muzzle, targets), random)) {
            val position = launchPosition(muzzle, shot.kind)
            state.entities.spawn { newProjectile(it, shot.kind, position, shot.velocity, massScale) }
        }
    }

    private fun runLevel(level: Int, dt: Float) {
        if (Config.Meteor.ENABLED) {
            for (spawn in spawner.update(dt, level)) spawnMeteor(spawn)
        }
        if (Config.Enemy.ENABLED) {
            for (spawn in npcSpawner.update(dt, level, Levels.of(level).enemies)) {
                state.entities.spawn { newNpc(it, spawn.kind, spawn.position, entry = spawn.entry) }
            }
        }
        if (state.run.phaseTime >= Config.Progression.LEVEL_DURATION) {
            spawner.reset()
            npcSpawner.reset()
            state.advancePhase()
        }
    }

    /** Босс появляется сверху; пока он жив, обычный спаун молчит, а окружение задаёт его текущая фаза. */
    private fun runBossFight(level: Int, dt: Float) {
        val boss = state.entities.ofType<Npc>().firstOrNull { it.isBoss } ?: spawnBoss(level)
        when (val environment = boss.currentPhase().environment) {
            Environment.Calm -> sideMeteors.reset()
            is Environment.SideMeteors -> sideMeteors.update(dt, environment.settings).forEach { spawnMeteor(it, entering = true) }
        }
    }

    private fun spawnBoss(level: Int): Npc {
        val kind = Levels.bossFor(level, random)
        val start = Vec2(field.width / 2, field.height + kind.body.hitbox.bottom)
        return state.entities.spawn { newNpc(it, kind, start) }
    }

    private fun spawnMeteor(spawn: MeteorSpawn, entering: Boolean = false) {
        state.entities.spawn { newMeteor(it, spawn.size, spawn.position, spawn.velocity, entering) }
    }

    /** Призывы из слотов и предмет-маяк: пока маяк в хранилище, рядом есть дрон, погибший возрождается через время. */
    private fun summonForPlayer(dt: Float) {
        for (source in state.takeSummonRequests()) PlayerSummons.specFor(source)?.let(npcs::summonForPlayer)
        if (PermanentItem.DroneBeacon !in state.run.storage.items) {
            beaconCooldown = 0f
            return
        }
        if (npcs.playerSummons(PlayerSummons.BEACON) > 0) {
            beaconCooldown = Config.Summon.BEACON_RESPAWN
            return
        }
        beaconCooldown -= dt
        if (beaconCooldown <= 0f && npcs.summonForPlayer(PlayerSummons.BEACON).isNotEmpty()) {
            beaconCooldown = Config.Summon.BEACON_RESPAWN
        }
    }

    /** Бестиарий: вид открывается, как только противник или союзник этого вида оказался на поле. */
    private fun markSeen() {
        for (npc in state.entities.ofType<Npc>()) {
            if (npc.kind.id !in state.meta.seen && isOnField(npc.position, field)) state.markSeen(npc.kind.id)
        }
    }

    /** Метеорит из-за бокового края, целиком оказавшийся на поле, снова отскакивает от стен. */
    private fun letMeteorsIn() {
        for (meteor in state.entities.ofType<Meteor>()) {
            if (meteor.entering && hasEntered(meteor, field)) state.entities.replace(meteor.entered())
        }
    }

    private fun handle(outcome: ImpactOutcome) {
        for (pickup in outcome.pickups) {
            val powerUp = state.entities[pickup.powerUp] as? PowerUp ?: continue
            if (state.pickUp(powerUp.type)) state.entities.remove(powerUp.id)
        }
        onDestroyed(outcome.destroyed)
    }

    /** Гибель от удара, луча или зоны: раскол и дроп, награда за босса, game over. */
    private fun onDestroyed(destroyed: List<Damageable>) {
        for (destroyed in destroyed) {
            when (destroyed) {
                is Meteor -> {
                    fragmentsOf(destroyed, Config.Meteor.LOOT, random).forEach { spawnMeteor(it, destroyed.entering) }
                    val loot = meteorLoot(destroyed.size, Config.Meteor.LOOT, state.meta.dropPool, random)
                    state.earn(loot.money)
                    loot.powerUp?.let { type -> state.entities.spawn { newPowerUp(it, type, destroyed.position) } }
                }
                is Npc -> when {
                    destroyed.isAlly -> Unit
                    destroyed.isBoss -> {
                        val loot = bossLoot(destroyed, random)
                        state.bossDefeated(loot.money, loot.item)
                    }
                    else -> {
                        // Вражеские саммоны денег и лута не дают, но засчитываются для переходов фаз «убить N».
                        if (destroyed.summon == null) {
                            val loot = npcLoot(destroyed.kind.loot, state.meta.dropPool, random)
                            state.earn(loot.money)
                            loot.powerUp?.let { type -> state.entities.spawn { newPowerUp(it, type, destroyed.position) } }
                        }
                        npcs.recordKill(destroyed.kind)
                    }
                }
                is Player -> state.gameOver() // от удара GAME_OVER уже выставлен физикой, повтор ничего не меняет
            }
        }
    }
}

// --- Спаун метеоритов ---

data class MeteorSpawn(val size: MeteorSize, val position: Vec2, val velocity: Vec2)

/**
 * @property difficultyStep на сколько чаще появляются метеориты с каждым уровнем (доля базовой частоты).
 * @property speedStep на сколько быстрее они летят с каждым уровнем.
 */
data class SpawnerSettings(
    val baseInterval: Float,
    val intervalJitter: Float,
    val minSpeed: Float,
    val maxSpeed: Float,
    val maxDrift: Float,
    val spawnHeight: Float,
    val difficultyStep: Float,
    val speedStep: Float,
    val sizeWeights: Map<MeteorSize, Float>,
) {
    init {
        require(baseInterval > 0f && intervalJitter in 0f..1f && minSpeed in 0f..maxSpeed) { "invalid spawner settings: $this" }
        require(sizeWeights.values.all { it >= 0f } && sizeWeights.values.sum() > 0f) { "invalid size weights: $sizeWeights" }
    }
}

/** Тайминги появления метеоритов над полем. Таймер — единственное состояние; случайность инъецируется. */
class MeteorSpawner(private val settings: SpawnerSettings, private val field: Field, private val random: Random) {
    private var timer = 0f

    fun reset() {
        timer = 0f
    }

    fun update(dt: Float, level: Int): List<MeteorSpawn> {
        timer -= dt
        if (timer > 0f) return emptyList()
        timer += interval(level)
        return listOf(spawn(level))
    }

    fun interval(level: Int): Float {
        val jitter = 1f + (random.nextFloat() * 2f - 1f) * settings.intervalJitter
        return settings.baseInterval / difficulty(level, settings.difficultyStep) * jitter
    }

    private fun spawn(level: Int): MeteorSpawn {
        val size = weightedPick(settings.sizeWeights, random)
        val hitbox = size.stats.hitbox
        val x = hitbox.halfWidth + random.nextFloat() * (field.width - 2 * hitbox.halfWidth)
        val y = field.height + hitbox.bottom + random.nextFloat() * settings.spawnHeight
        val speed = (settings.minSpeed + random.nextFloat() * (settings.maxSpeed - settings.minSpeed)) *
            difficulty(level, settings.speedStep)
        val drift = (random.nextFloat() * 2f - 1f) * settings.maxDrift
        return MeteorSpawn(size, Vec2(x, y), Vec2(drift, -speed))
    }
}

/** Множитель сложности: 1 на первом уровне, +step за каждый следующий. */
fun difficulty(level: Int, step: Float): Float = 1f + (level - 1).coerceAtLeast(0) * step

fun <T> weightedPick(weights: Map<T, Float>, random: Random): T {
    var roll = random.nextFloat() * weights.values.sum()
    for ((item, weight) in weights) {
        roll -= weight
        if (roll < 0f) return item
    }
    return weights.keys.last { weights.getValue(it) > 0f }
}

// --- Лут ---

/**
 * @property fragments во что раскалывается метеорит данного размера (нет ключа — не раскалывается).
 * @property fragmentOffset на какую долю радиуса родителя осколки смещены от его центра.
 */
data class LootSettings(
    val fragments: Map<MeteorSize, MeteorSize>,
    val fragmentCount: IntRange,
    val fragmentSpeed: Float,
    val fragmentOffset: Float,
    val moneyChance: Float,
    val money: Map<MeteorSize, IntRange>,
    val powerUpChance: Float,
) {
    init {
        require(moneyChance in 0f..1f && powerUpChance in 0f..1f) { "chances out of [0; 1]: $this" }
        require(fragmentCount.first >= 1) { "invalid loot settings: $this" }
    }
}

data class MeteorLoot(val money: Int, val powerUp: PowerUpType?)

data class BossLoot(val money: Int, val item: PermanentItem)

/** Осколки разлетаются равномерно по кругу (со случайным поворотом), унося скорость родителя. */
fun fragmentsOf(meteor: Meteor, settings: LootSettings, random: Random): List<MeteorSpawn> {
    val size = settings.fragments[meteor.size] ?: return emptyList()
    val count = random.nextInt(settings.fragmentCount.first, settings.fragmentCount.last + 1)
    val turn = random.nextFloat() * FULL_TURN
    return List(count) { i ->
        val angle = turn + FULL_TURN * i / count
        val direction = Vec2(cos(angle), sin(angle))
        MeteorSpawn(
            size = size,
            position = meteor.position + direction * (meteor.radius * settings.fragmentOffset),
            velocity = meteor.velocity + direction * settings.fragmentSpeed,
        )
    }
}

/** [powerUps] — что сейчас может выпасть (базовые усилители и купленное в магазине меню). */
fun meteorLoot(size: MeteorSize, settings: LootSettings, powerUps: List<PowerUpType>, random: Random): MeteorLoot {
    val money = if (random.nextFloat() < settings.moneyChance) {
        settings.money[size]?.let { random.nextInt(it.first, it.last + 1) } ?: 0
    } else {
        0
    }
    val powerUp = if (powerUps.isNotEmpty() && random.nextFloat() < settings.powerUpChance) powerUps.random(random) else null
    return MeteorLoot(money, powerUp)
}

/** Деньги с противника — всегда в пределах [NpcLoot.money], усилитель — с шансом. */
fun npcLoot(loot: NpcLoot, powerUps: List<PowerUpType>, random: Random): MeteorLoot {
    val money = random.nextInt(loot.money.first, loot.money.last + 1)
    val powerUp = if (powerUps.isNotEmpty() && random.nextFloat() < loot.powerUpChance) powerUps.random(random) else null
    return MeteorLoot(money, powerUp)
}

/** С босса всегда: деньги и трофей. */
fun bossLoot(boss: Npc, random: Random): BossLoot {
    val money = boss.kind.loot.money
    return BossLoot(random.nextInt(money.first, money.last + 1), PermanentItem.Trophy(boss.kind))
}

private const val FULL_TURN = (2 * PI).toFloat()
