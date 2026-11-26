package com.spaceace.game.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// --- Вектор ---

/** Неизменяемый вектор логики игры (без LibGDX). Единицы — метры и м/с. */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)
    operator fun minus(other: Vec2) = Vec2(x - other.x, y - other.y)
    operator fun times(scale: Float) = Vec2(x * scale, y * scale)
    operator fun unaryMinus() = Vec2(-x, -y)
    infix fun dot(other: Vec2) = x * other.x + y * other.y

    val lengthSquared: Float get() = x * x + y * y
    val length: Float get() = sqrt(lengthSquared)

    /** Единичный вектор того же направления; нулевой остаётся нулевым. */
    fun normalized(): Vec2 {
        val len = length
        return if (len == 0f) ZERO else Vec2(x / len, y / len)
    }

    companion object {
        val ZERO = Vec2(0f, 0f)
    }
}

// --- Поле ---

/** Размер игрового поля в метрах; начало — левый нижний угол. */
data class Field(val width: Float, val height: Float)

// --- Углы ---

/*
 * Углы корпуса в радианах, против часовой стрелки. Угол 0 — нос смотрит вверх по полю (+Y), π — вниз.
 */

const val TWO_PI = (2 * PI).toFloat()

/** Единичное направление носа при повороте корпуса на [angle]. */
fun noseDirection(angle: Float): Vec2 = Vec2(-sin(angle), cos(angle))

/** Угол корпуса, при котором нос смотрит по [direction]. */
fun noseAngle(direction: Vec2): Float = atan2(-direction.x, direction.y)

/** Угол в (−π; π]. */
fun wrapAngle(angle: Float): Float {
    var a = angle % TWO_PI
    if (a > PI) a -= TWO_PI
    if (a <= -PI) a += TWO_PI
    return a
}

/** Угол в [0; 2π). */
fun normalizeAngle(angle: Float): Float = ((angle % TWO_PI) + TWO_PI) % TWO_PI

/** Поворот вектора против часовой стрелки на [radians]. */
fun Vec2.rotatedBy(radians: Float): Vec2 {
    val c = cos(radians)
    val s = sin(radians)
    return Vec2(x * c - y * s, x * s + y * c)
}

/** Угол между направлениями без знака, [0; π]; нулевой вектор — 0. */
fun angleBetween(a: Vec2, b: Vec2): Float {
    val na = a.normalized()
    val nb = b.normalized()
    if (na == Vec2.ZERO || nb == Vec2.ZERO) return 0f
    return acos((na dot nb).coerceIn(-1f, 1f))
}

/** Угол от [from] до [to] со знаком (против часовой — плюс), (−π; π]. */
fun signedAngle(from: Vec2, to: Vec2): Float = wrapAngle(atan2(to.y, to.x) - atan2(from.y, from.x))

fun degrees(value: Float): Float = Math.toRadians(value.toDouble()).toFloat()

// --- Хитбоксы ---

/**
 * Постоянный поворот фигуры против часовой стрелки на [degrees] (любой угол, например 45°).
 * Для треугольника R0 — основание внизу (вершина смотрит вверх), R180 — основание вверху.
 * Тело с таким хитбоксом не вращается: поворот задан один раз в форме.
 */
@JvmInline
value class Rotation(val degrees: Float) {
    fun apply(v: Vec2): Vec2 = when (quarterTurns()) {
        // Кратные 90° — точно, без погрешности sin/cos: на них держатся ровные стороны прямоугольников.
        0 -> v
        1 -> Vec2(-v.y, v.x)
        2 -> Vec2(-v.x, -v.y)
        3 -> Vec2(v.y, -v.x)
        else -> {
            val a = Math.toRadians(degrees.toDouble())
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            Vec2(v.x * c - v.y * s, v.x * s + v.y * c)
        }
    }

    /** 0..3 — поворот на целое число четвертей; иначе null. */
    private fun quarterTurns(): Int? {
        val normalized = ((degrees % FULL_TURN_DEG) + FULL_TURN_DEG) % FULL_TURN_DEG
        return if (normalized % QUARTER_DEG == 0f) (normalized / QUARTER_DEG).toInt() else null
    }

    companion object {
        private const val FULL_TURN_DEG = 360f
        private const val QUARTER_DEG = 90f

        val R0 = Rotation(0f)
        val R90 = Rotation(90f)
        val R180 = Rotation(180f)
        val R270 = Rotation(270f)
        val QUARTERS = listOf(R0, R90, R180, R270)
    }
}

/**
 * Форма тела в метрах, центр — центр масс (совпадает с position сущности).
 * Одна и та же форма идёт в Box2D и в рендер, поэтому картинка всегда совпадает с хитбоксом.
 */
sealed interface Hitbox {
    /** Вершины против часовой стрелки относительно центра; null — круг. */
    val vertices: List<Vec2>?
    val area: Float

    /** Радиус описанной окружности: границы поля, грубые проверки расстояния. */
    val boundingRadius: Float

    /** Наибольшее |x| — для упора в стены. */
    val halfWidth: Float

    /** Насколько фигура выступает вверх/вниз от центра — для позиции дула. */
    val top: Float
    val bottom: Float

    data class Circle(val radius: Float) : Hitbox {
        init {
            require(radius > 0f) { "radius must be positive: $radius" }
        }

        override val vertices: List<Vec2>? get() = null
        override val area: Float get() = (PI * radius * radius).toFloat()
        override val boundingRadius: Float get() = radius
        override val halfWidth: Float get() = radius
        override val top: Float get() = radius
        override val bottom: Float get() = radius
    }

    data class Rect(val width: Float, val height: Float, val rotation: Rotation = Rotation.R0) : Polygon() {
        override val vertices: List<Vec2> = run {
            val w = width / 2
            val h = height / 2
            listOf(Vec2(-w, -h), Vec2(w, -h), Vec2(w, h), Vec2(-w, h)).map(rotation::apply)
        }
    }

    /** Равнобедренный треугольник: основание [base], высота [height]; центр — центроид (треть высоты от основания). */
    data class Triangle(val base: Float, val height: Float, val rotation: Rotation = Rotation.R0) : Polygon() {
        override val vertices: List<Vec2> = listOf(
            Vec2(-base / 2, -height / 3),
            Vec2(base / 2, -height / 3),
            Vec2(0f, height * 2 / 3),
        ).map(rotation::apply)
    }

    /**
     * Эллипс с полуосями [radiusX], [radiusY]. Box2D держит не больше 8 вершин у полигона,
     * поэтому и физика, и рендер используют вписанный 8-угольник.
     */
    data class Ellipse(val radiusX: Float, val radiusY: Float, val rotation: Rotation = Rotation.R0) : Polygon() {
        override val vertices: List<Vec2> = List(ELLIPSE_VERTICES) { i ->
            val angle = 2 * PI * i / ELLIPSE_VERTICES
            rotation.apply(Vec2((radiusX * cos(angle)).toFloat(), (radiusY * sin(angle)).toFloat()))
        }
    }

    /** Общие вычисления для выпуклых многоугольников (данные считаются один раз на форму). */
    sealed class Polygon : Hitbox {
        abstract override val vertices: List<Vec2>

        private val metrics by lazy {
            require(vertices.size in 3..MAX_POLYGON_VERTICES) { "polygon needs 3..$MAX_POLYGON_VERTICES vertices: $vertices" }
            val doubleArea = vertices.indices.sumOf { i ->
                val a = vertices[i]
                val b = vertices[(i + 1) % vertices.size]
                (a.x * b.y - b.x * a.y).toDouble()
            }
            require(doubleArea > 0) { "vertices must be counter-clockwise: $vertices" }
            floatArrayOf(
                (doubleArea / 2).toFloat(),
                vertices.maxOf { it.length },
                vertices.maxOf { abs(it.x) },
                vertices.maxOf { it.y },
                -vertices.minOf { it.y },
            )
        }

        override val area: Float get() = metrics[0]
        override val boundingRadius: Float get() = metrics[1]
        override val halfWidth: Float get() = metrics[2]
        override val top: Float get() = metrics[3]
        override val bottom: Float get() = metrics[4]
    }

    companion object {
        const val MAX_POLYGON_VERTICES = 8
        const val ELLIPSE_VERTICES = 8
    }
}
