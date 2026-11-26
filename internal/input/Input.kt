package com.spaceace.game.input

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input.Keys
import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.utils.viewport.Viewport
import com.spaceace.game.Config
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

// --- Управление кораблём ---

/**
 * Снимок управления за шаг.
 * @property targetX, targetY точка, к которой стремится корабль (палец/мышь), в метрах поля; null — указателя нет.
 * @property axis, axisY клавиатурные оси −1..1; если не 0, важнее указателя по своей оси.
 */
data class ControlState(
    val targetX: Float? = null,
    val targetY: Float? = null,
    val axis: Float = 0f,
    val axisY: Float = 0f,
    val fire: Boolean = false,
)

interface InputSource {
    fun poll(): ControlState
}

data class SteeringSettings(val maxSpeed: Float, val maxSpeedY: Float, val gain: Float, val deadZone: Float, val maxAccel: Float)

/**
 * Ограничение подъёма: до высоты [fullSpeedY] корабль поднимается с полной скоростью, к [ceilingY] скорость
 * подъёма падает до нуля (по закону степени [exponent]), выше потолка корабль плавно опускается обратно.
 */
data class ClimbSettings(val fullSpeedY: Float, val ceilingY: Float, val exponent: Float) {
    init {
        require(ceilingY > fullSpeedY && exponent > 0f) { "invalid climb settings: $this" }
    }
}

/** Желаемая скорость по X: корабль догоняет палец, но не быстрее maxSpeed. */
fun targetVelocityX(currentX: Float, control: ControlState, settings: SteeringSettings): Float =
    chase(currentX, control.targetX, control.axis, settings.maxSpeed, settings)

/** Желаемая скорость по Y: как по X, но вверх не быстрее, чем позволяет высота ([maxClimbSpeed]); вниз — без ограничений. */
fun targetVelocityY(currentY: Float, control: ControlState, settings: SteeringSettings, climb: ClimbSettings): Float {
    val wanted = chase(currentY, control.targetY, control.axisY, settings.maxSpeedY, settings)
    return minOf(wanted, maxClimbSpeed(currentY, settings.maxSpeedY, climb))
}

/**
 * Наибольшая скорость подъёма на высоте [y]. Положительна ниже потолка, ноль на потолке,
 * отрицательна выше него: даже без ввода корабль, подброшенный ударом, опускается к потолку.
 */
fun maxClimbSpeed(y: Float, maxSpeedY: Float, climb: ClimbSettings): Float {
    val room = ((climb.ceilingY - y) / (climb.ceilingY - climb.fullSpeedY)).coerceIn(-1f, 1f)
    return maxSpeedY * sign(room) * abs(room).pow(climb.exponent)
}

/**
 * Импульс по одной оси, приближающий скорость к целевой с ограниченным ускорением.
 * Скорость не выставляется напрямую — так толчок от столкновения гаснет постепенно, а не стирается.
 */
fun steeringImpulse(mass: Float, currentVx: Float, targetVx: Float, maxAccel: Float, dt: Float): Float {
    val maxDelta = maxAccel * dt
    return mass * (targetVx - currentVx).coerceIn(-maxDelta, maxDelta)
}

private fun chase(current: Float, target: Float?, axis: Float, maxSpeed: Float, settings: SteeringSettings): Float {
    if (axis != 0f) return axis.coerceIn(-1f, 1f) * maxSpeed
    target ?: return 0f
    val delta = target - current
    if (abs(delta) < settings.deadZone) return 0f
    return (delta * settings.gain).coerceIn(-maxSpeed, maxSpeed)
}

// --- Раскладка клавиш ---

/** Игровые действия, которые можно назначить на клавишу. Порядок — порядок строк на экране настроек. */
enum class Control {
    FIRE, SLOT_1, SLOT_2, SLOT_3, SLOT_4, SLOT_5, MOVE_LEFT, MOVE_RIGHT, MOVE_UP, MOVE_DOWN, PAUSE;

    /** Номер слота (с 0) для SLOT_1..SLOT_5, иначе null. */
    val slotIndex: Int? get() = SLOTS.indexOf(this).takeIf { it >= 0 }

    companion object {
        val SLOTS = listOf(SLOT_1, SLOT_2, SLOT_3, SLOT_4, SLOT_5)
    }
}

/**
 * Раскладка: клавиша (keycode LibGDX) у каждого действия. У действия может не быть клавиши;
 * одна клавиша — не больше чем у одного действия.
 */
data class KeyBindings(private val keys: Map<Control, Int>) {
    init {
        require(keys.values.toSet().size == keys.size) { "one key bound to several controls: $keys" }
    }

    fun keyOf(control: Control): Int? = keys[control]

    fun controlOf(key: Int): Control? = keys.entries.firstOrNull { it.value == key }?.key

    /** Клавиша, занятая другим действием, снимается с него — то действие остаётся без клавиши. */
    fun bind(control: Control, key: Int): KeyBindings = KeyBindings(keys.filterValues { it != key } + (control to key))

    companion object {
        val DEFAULT = KeyBindings(Config.Controls.DEFAULT_KEYS)
    }
}

/** Клавиатура поверх указателя (мышь работает как палец): движение и огонь по раскладке. */
fun keyboardControl(pointer: ControlState, bindings: KeyBindings, pressed: (Int) -> Boolean): ControlState {
    fun held(control: Control) = bindings.keyOf(control)?.let(pressed) ?: false
    fun axis(positive: Control, negative: Control) = (if (held(positive)) 1f else 0f) - (if (held(negative)) 1f else 0f)
    return pointer.copy(
        axis = axis(Control.MOVE_RIGHT, Control.MOVE_LEFT),
        axisY = axis(Control.MOVE_UP, Control.MOVE_DOWN),
        fire = pointer.fire || held(Control.FIRE),
    )
}

/** Подпись клавиши для экрана настроек: имя LibGDX заглавными («LEFT», «S», «SPACE»). */
fun keyName(key: Int): String = Keys.toString(key)?.uppercase() ?: "KEY $key"

// --- Касания ---

/** Попадает ли точка (виртуальные px) в прозрачную кнопку огня. */
fun isOnFireButton(x: Float, y: Float): Boolean {
    val dx = x - Config.Ui.FIRE_X
    val dy = y - Config.Ui.FIRE_Y
    return dx * dx + dy * dy <= Config.Ui.FIRE_RADIUS * Config.Ui.FIRE_RADIUS
}

/**
 * Мультитач: палец, нажатый на кнопке огня, держит огонь; любой другой палец ведёт корабль.
 * Касания кнопок UI сюда не доходят — их забирает UI-процессор раньше в InputMultiplexer.
 * Роль пальца фиксируется при нажатии: утащенный на кнопку огня палец продолжает вести корабль.
 */
class TouchInput(private val viewport: Viewport) : InputAdapter(), InputSource {
    private val firePointers = HashSet<Int>()
    private var steerPointer: Int? = null
    private var steerX: Float? = null
    private var steerY: Float? = null
    private val point = Vector2()

    override fun poll() = ControlState(targetX = steerX, targetY = steerY, fire = firePointers.isNotEmpty())

    fun reset() {
        firePointers.clear()
        steerPointer = null
        steerX = null
        steerY = null
    }

    override fun touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        toVirtual(screenX, screenY)
        if (isOnFireButton(point.x, point.y)) {
            firePointers += pointer
        } else {
            steerPointer = pointer
            aim()
        }
        return true
    }

    override fun touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean {
        if (pointer != steerPointer) return false
        toVirtual(screenX, screenY)
        aim()
        return true
    }

    override fun touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean {
        firePointers -= pointer
        if (pointer == steerPointer) {
            steerPointer = null
            steerX = null
            steerY = null
        }
        return true
    }

    override fun touchCancelled(screenX: Int, screenY: Int, pointer: Int, button: Int) =
        touchUp(screenX, screenY, pointer, button)

    /** Корабль догоняет точку под пальцем по X и чуть выше пальца по Y. */
    private fun aim() {
        steerX = point.x / Config.Physics.PIXELS_PER_METER
        steerY = point.y / Config.Physics.PIXELS_PER_METER + Config.Player.FINGER_OFFSET_Y
    }

    private fun toVirtual(screenX: Int, screenY: Int) {
        viewport.unproject(point.set(screenX.toFloat(), screenY.toFloat()))
    }
}

// --- Клавиатура и мышь ---

/** Мышь работает как палец (через [touch]); клавиатура — по текущей раскладке [bindings]. */
class DesktopInput(private val touch: TouchInput, private val bindings: () -> KeyBindings) : InputSource {
    override fun poll(): ControlState = keyboardControl(touch.poll(), bindings()) { Gdx.input.isKeyPressed(it) }
}
