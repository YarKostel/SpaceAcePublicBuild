package com.spaceace.game.input

import com.badlogic.gdx.Input.Keys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SteeringTest {
    private val eps = 1e-5f
    private val settings = SteeringSettings(maxSpeed = 5f, maxSpeedY = 4f, gain = 2f, deadZone = 0.1f, maxAccel = 30f)
    private val climb = ClimbSettings(fullSpeedY = 2f, ceilingY = 8f, exponent = 1f)

    @Test
    fun `no input means stop`() {
        assertEquals(0f, targetVelocityX(3f, ControlState(), settings))
    }

    @Test
    fun `pointer pulls proportionally and is capped`() {
        assertEquals(2f, targetVelocityX(3f, ControlState(targetX = 4f), settings), eps)
        assertEquals(-5f, targetVelocityX(3f, ControlState(targetX = -10f), settings), eps)
    }

    @Test
    fun `dead zone prevents jitter near the finger`() {
        assertEquals(0f, targetVelocityX(3f, ControlState(targetX = 3.05f), settings))
    }

    @Test
    fun `keyboard axis overrides pointer`() {
        assertEquals(-5f, targetVelocityX(3f, ControlState(targetX = 6f, axis = -1f), settings), eps)
    }

    @Test
    fun `impulse is limited by max acceleration`() {
        // Δv за шаг не больше maxAccel·dt = 0.5 м/с.
        assertEquals(2f * 0.5f, steeringImpulse(mass = 2f, currentVx = 0f, targetVx = 5f, maxAccel = 30f, dt = 1f / 60f), eps)
        assertEquals(2f * 0.2f, steeringImpulse(mass = 2f, currentVx = 4.8f, targetVx = 5f, maxAccel = 30f, dt = 1f / 60f), eps)
    }

    @Test
    fun `knockback decays instead of being erased`() {
        val impulse = steeringImpulse(mass = 1f, currentVx = 6f, targetVx = 0f, maxAccel = 30f, dt = 1f / 60f)
        assertEquals(-0.5f, impulse, eps)
    }

    @Test
    fun `climb is full speed low, slower higher and zero at the ceiling`() {
        assertEquals(4f, maxClimbSpeed(1f, 4f, climb), eps)
        assertEquals(4f, maxClimbSpeed(2f, 4f, climb), eps)
        assertEquals(2f, maxClimbSpeed(5f, 4f, climb), eps)
        assertEquals(0f, maxClimbSpeed(8f, 4f, climb), eps)
        val speeds = (20..80).map { maxClimbSpeed(it / 10f, 4f, climb) }
        assertTrue(speeds.zipWithNext().all { (a, b) -> a >= b }, "climb speed must not grow with height")
    }

    @Test
    fun `above the ceiling the ship sinks back`() {
        assertTrue(maxClimbSpeed(9f, 4f, climb) < 0f)
        assertEquals(-4f, maxClimbSpeed(20f, 4f, climb), eps, "sinking is capped at full speed")
    }

    @Test
    fun `steeper exponent slows the climb earlier`() {
        val linear = maxClimbSpeed(5f, 4f, climb)
        val quadratic = maxClimbSpeed(5f, 4f, climb.copy(exponent = 2f))
        assertTrue(quadratic < linear)
    }

    @Test
    fun `going up is limited by height, going down is not`() {
        val up = ControlState(axisY = 1f)
        assertEquals(4f, targetVelocityY(1f, up, settings, climb), eps)
        assertEquals(2f, targetVelocityY(5f, up, settings, climb), eps)
        assertEquals(0f, targetVelocityY(8f, up, settings, climb), eps)
        assertEquals(-4f, targetVelocityY(7.9f, ControlState(axisY = -1f), settings, climb), eps)
    }

    @Test
    fun `pointer pulls vertically like horizontally`() {
        assertEquals(2f, targetVelocityY(3f, ControlState(targetY = 4f), settings, climb), eps)
        assertEquals(-4f, targetVelocityY(5f, ControlState(targetY = 1f), settings, climb), eps)
        assertEquals(0f, targetVelocityY(3f, ControlState(targetY = 3.05f), settings, climb), "dead zone")
    }

    @Test
    fun `without input the ship holds its height below the ceiling`() {
        assertEquals(0f, targetVelocityY(5f, ControlState(), settings, climb))
        assertTrue(targetVelocityY(9f, ControlState(), settings, climb) < 0f, "but drifts down from above it")
    }

    @Test
    fun `invalid climb settings are rejected`() {
        assertFailsWith<IllegalArgumentException> { ClimbSettings(fullSpeedY = 5f, ceilingY = 5f, exponent = 1f) }
    }
}

class ControlsTest {
    private val defaults = KeyBindings.DEFAULT

    @Test
    fun `default layout is S to fire, A D Z X C for slots, arrows to move, P to pause`() {
        assertEquals(Keys.S, defaults.keyOf(Control.FIRE))
        assertEquals(listOf(Keys.A, Keys.D, Keys.Z, Keys.X, Keys.C), Control.SLOTS.map(defaults::keyOf))
        assertEquals(Keys.LEFT, defaults.keyOf(Control.MOVE_LEFT))
        assertEquals(Keys.RIGHT, defaults.keyOf(Control.MOVE_RIGHT))
        assertEquals(Keys.UP, defaults.keyOf(Control.MOVE_UP))
        assertEquals(Keys.DOWN, defaults.keyOf(Control.MOVE_DOWN))
        assertEquals(Keys.P, defaults.keyOf(Control.PAUSE))
        assertTrue(Control.entries.all { defaults.keyOf(it) != null }, "every control has a key by default")
    }

    @Test
    fun `slots know their index`() {
        assertEquals(listOf(0, 1, 2, 3, 4), Control.SLOTS.map { it.slotIndex })
        assertNull(Control.FIRE.slotIndex)
    }

    @Test
    fun `key is found by control and control by key`() {
        assertEquals(Control.SLOT_2, defaults.controlOf(Keys.D))
        assertNull(defaults.controlOf(Keys.Q))
    }

    @Test
    fun `binding a free key replaces the old one`() {
        val changed = defaults.bind(Control.FIRE, Keys.SPACE)
        assertEquals(Keys.SPACE, changed.keyOf(Control.FIRE))
        assertNull(changed.controlOf(Keys.S), "old key is free now")
    }

    @Test
    fun `binding a taken key takes it away from the other control`() {
        val changed = defaults.bind(Control.FIRE, Keys.A)
        assertEquals(Keys.A, changed.keyOf(Control.FIRE))
        assertNull(changed.keyOf(Control.SLOT_1), "slot 1 is left without a key")
        assertEquals(Control.FIRE, changed.controlOf(Keys.A))
    }

    @Test
    fun `rebinding a control to its own key changes nothing`() {
        assertEquals(defaults, defaults.bind(Control.FIRE, Keys.S))
    }

    @Test
    fun `one key cannot belong to two controls`() {
        assertFailsWith<IllegalArgumentException> { KeyBindings(mapOf(Control.FIRE to Keys.S, Control.PAUSE to Keys.S)) }
    }

    @Test
    fun `keyboard moves and fires by the bindings`() {
        val held = setOf(Keys.LEFT, Keys.UP, Keys.S)
        val state = keyboardControl(ControlState(), defaults) { it in held }
        assertEquals(-1f, state.axis)
        assertEquals(1f, state.axisY)
        assertTrue(state.fire)
    }

    @Test
    fun `old keys do nothing after rebinding and unbound controls are never held`() {
        val bindings = defaults.bind(Control.FIRE, Keys.SPACE).bind(Control.MOVE_LEFT, Keys.S)
        val withS = keyboardControl(ControlState(), bindings) { it == Keys.S }
        assertFalse(withS.fire, "S moves left now")
        assertEquals(-1f, withS.axis)
        val noKeys = keyboardControl(ControlState(), defaults.bind(Control.PAUSE, Keys.UP)) { it == Keys.UP }
        assertEquals(0f, noKeys.axisY, "MOVE_UP lost its key")
    }

    @Test
    fun `pointer fire and target survive the keyboard`() {
        val pointer = ControlState(targetX = 2f, targetY = 3f, fire = true)
        val state = keyboardControl(pointer, defaults) { false }
        assertEquals(2f, state.targetX)
        assertEquals(3f, state.targetY)
        assertTrue(state.fire)
    }

    @Test
    fun `key names are upper case`() {
        assertEquals("S", keyName(Keys.S))
        assertEquals("LEFT", keyName(Keys.LEFT))
        assertEquals("SPACE", keyName(Keys.SPACE))
    }
}
