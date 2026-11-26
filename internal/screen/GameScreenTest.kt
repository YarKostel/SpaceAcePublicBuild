package com.spaceace.game.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FixedStepTest {
    private val step = 1f / 60f
    private val settings = FixedStepSettings(step = step, maxFrameTime = 0.25f, maxStepsPerFrame = 5)
    private val eps = 1e-5f

    @Test
    fun `exact frame produces one step and empty remainder`() {
        val plan = planFixedSteps(0f, step, settings)
        assertEquals(1, plan.steps)
        assertEquals(0f, plan.remainder, eps)
    }

    @Test
    fun `short frames accumulate until a full step`() {
        val first = planFixedSteps(0f, step / 2, settings)
        assertEquals(0, first.steps)
        val second = planFixedSteps(first.remainder, step / 2, settings)
        assertEquals(1, second.steps)
        assertEquals(0f, second.remainder, eps)
    }

    @Test
    fun `steps are capped and backlog is dropped`() {
        val plan = planFixedSteps(0f, 10 * step, settings)
        assertEquals(5, plan.steps)
        assertTrue(plan.remainder < step)
    }

    @Test
    fun `huge frame is clamped to max frame time`() {
        val wide = FixedStepSettings(step = step, maxFrameTime = 0.25f, maxStepsPerFrame = 100)
        val plan = planFixedSteps(0f, 5f, wide)
        assertEquals((0.25f / step).toInt(), plan.steps)
    }

    @Test
    fun `negative delta is rejected`() {
        assertFailsWith<IllegalArgumentException> { planFixedSteps(0f, -0.01f, settings) }
    }

    @Test
    fun `invalid settings are rejected`() {
        assertFailsWith<IllegalArgumentException> { FixedStepSettings(0f, 0.25f, 5) }
        assertFailsWith<IllegalArgumentException> { FixedStepSettings(step, step / 2, 5) }
        assertFailsWith<IllegalArgumentException> { FixedStepSettings(step, 0.25f, 0) }
    }

    @Test
    fun `stepper keeps alpha in unit range over many uneven frames`() {
        val stepper = FixedStepper(settings)
        var total = 0
        repeat(600) { i ->
            total += stepper.consume(if (i % 2 == 0) 0.010f else 0.0233f)
            assertTrue(stepper.alpha >= 0f && stepper.alpha < 1f)
        }
        // 300 * (0.010 + 0.0233) с ≈ 9.99 с ≈ 599 шагов по 1/60 с
        assertTrue(total in 597..600)
    }
}
