package com.spaceace.game.logic

import com.badlogic.gdx.physics.box2d.Box2D
import com.spaceace.game.Config
import com.spaceace.game.input.ControlState
import com.spaceace.game.input.InputSource
import com.spaceace.game.model.Health
import com.spaceace.game.model.Meteor
import com.spaceace.game.model.Npc
import com.spaceace.game.model.PowerUpType
import com.spaceace.game.model.WeaponType
import com.spaceace.game.physics.PhysicsSystem
import com.spaceace.game.state.GameState
import com.spaceace.game.state.MetaState
import com.spaceace.game.state.Phase
import com.spaceace.game.state.ScreenState
import com.spaceace.game.state.Slot
import com.spaceace.game.state.Wallet
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Бот ведёт корабль под самую низкую цель выше себя и жмёт огонь циклами «держать — отпустить»:
 * заряжаемое оружие стреляет только при отпускании.
 */
private class Bot(private val state: GameState) : InputSource {
    private var tick = 0

    override fun poll(): ControlState {
        val player = state.player ?: return ControlState()
        val target = state.entities.all
            .filter { (it is Meteor || (it is Npc && !it.isAlly)) && it.position.y > player.position.y }
            .minByOrNull { it.position.y }
        val fire = tick++ % FIRE_CYCLE < HOLD_STEPS
        return ControlState(targetX = target?.position?.x, fire = fire)
    }

    private companion object {
        const val FIRE_CYCLE = 60
        const val HOLD_STEPS = 54
    }
}

/** Полный забег на настоящем Box2D без окна: проверяет, что все системы стыкуются во время работы. */
class GameLoopTest {
    private val dt = Config.Physics.TIME_STEP
    private val maxSteps = (20 * 60 / dt).toInt() // 20 минут игрового времени

    @BeforeTest
    fun loadBox2D() = Box2D.init()

    private data class RunResult(
        val screen: ScreenState,
        val phases: Set<Phase>,
        val seconds: Float,
        val money: Int,
        val weapon: WeaponType,
        val bossHealth: Float?,
    )

    private fun play(seed: Int, invulnerable: Boolean): RunResult {
        val random = Random(seed)
        val state = GameState(MetaState(Wallet(0)), random)
        val physics = PhysicsSystem(state)
        val logic = GameLogic(state, physics, Bot(state), random)
        val phases = LinkedHashSet<Phase>()
        var steps = 0
        try {
            state.startRun()
            while (steps < maxSteps) {
                when (state.screen) {
                    ScreenState.PLAYING -> {
                        if (invulnerable) keepAlive(state)
                        // Тратит расходники и включает готовые постоянные усилители; оружие не переключает.
                        state.run.inventory.slots.indexOfFirst { it is Slot.Supply || (it is Slot.Gear && it.ready) }
                            .takeIf { it >= 0 }?.let(state::activateSlot)
                        logic.step(dt)
                        phases += state.run.phase
                        steps++
                    }
                    ScreenState.SHOP -> {
                        state.run.shop?.offers?.indices?.forEach(state::buy)
                        state.advancePhase()
                    }
                    else -> break
                }
            }
        } finally {
            physics.dispose()
        }
        val boss = state.entities.ofType<Npc>().firstOrNull { it.isBoss }
        return RunResult(state.screen, phases, steps * dt, state.meta.wallet.balance, state.run.weapon, boss?.health?.current)
    }

    /**
     * Лечение между шагами не спасает от удара сильнее всего запаса HP за один шаг,
     * поэтому бессмертие держится на щите: при щите урон по кораблю нулевой.
     */
    private fun keepAlive(state: GameState) {
        state.player?.let { state.entities.replace(it.withHealth(Health.full(it.health.max))) }
        if (state.run.effects.isActive(PowerUpType.Shield) || !state.pickUp(PowerUpType.Shield)) return
        state.activateSlot(state.run.inventory.slots.indexOfFirst { it is Slot.Supply && it.type == PowerUpType.Shield })
    }

    @Test
    fun `invulnerable bot beats both bosses and wins`() {
        val result = play(seed = 42, invulnerable = true)
        assertEquals(ScreenState.VICTORY, result.screen, "$result")
        assertTrue(Phase.BossFight(1) in result.phases && Phase.Level(2) in result.phases, "$result")
        assertTrue(result.money > 0, "$result")
    }

    @Test
    fun `ordinary runs end in victory or game over without errors`() {
        for (seed in 1..3) {
            val result = play(seed, invulnerable = false)
            assertTrue(result.screen == ScreenState.VICTORY || result.screen == ScreenState.GAME_OVER, "$result")
            println("seed $seed: $result")
        }
    }
}
