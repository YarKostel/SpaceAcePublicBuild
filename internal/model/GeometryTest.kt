package com.spaceace.game.model

import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HitboxTest {
    private val eps = 1e-4f

    private fun centroid(vertices: List<Vec2>): Vec2 = vertices.reduce(Vec2::plus) * (1f / vertices.size)

    @Test
    fun `circle has no vertices and pi r squared area`() {
        val circle = Hitbox.Circle(0.5f)
        assertNull(circle.vertices)
        assertEquals((PI * 0.25).toFloat(), circle.area, eps)
        assertEquals(0.5f, circle.top)
    }

    @Test
    fun `rectangle metrics`() {
        val rect = Hitbox.Rect(2f, 1f)
        assertEquals(2f, rect.area, eps)
        assertEquals(1f, rect.halfWidth, eps)
        assertEquals(0.5f, rect.top, eps)
        assertEquals(sqrt(1.25f), rect.boundingRadius, eps)
    }

    @Test
    fun `triangle is centered on its centroid and points up by default`() {
        val triangle = Hitbox.Triangle(base = 0.6f, height = 0.9f)
        assertEquals(0.6f * 0.9f / 2, triangle.area, eps)
        val c = centroid(triangle.vertices)
        assertEquals(0f, c.x, eps)
        assertEquals(0f, c.y, eps)
        assertEquals(0.6f, triangle.top, eps)
        assertEquals(0.3f, triangle.bottom, eps)
    }

    @Test
    fun `rotations keep area and turn the apex`() {
        val up = Hitbox.Triangle(1f, 1.5f, Rotation.R0)
        val left = Hitbox.Triangle(1f, 1.5f, Rotation.R90)
        val down = Hitbox.Triangle(1f, 1.5f, Rotation.R180)
        for (t in listOf(left, down)) assertEquals(up.area, t.area, eps)
        // R180: основание вверху, вершина внизу.
        assertEquals(up.bottom, down.top, eps)
        assertEquals(up.top, down.bottom, eps)
        // R90: вершина смотрит влево, по высоте фигура — это бывшая ширина основания.
        assertEquals(0.5f, left.top, eps)
        assertEquals(1f, left.halfWidth, eps)
    }

    @Test
    fun `equilateral triangle has equal distances to all vertices`() {
        val side = 0.8f
        val t = Hitbox.Triangle(side, side * sqrt(3f) / 2)
        val distances = t.vertices.map { it.length }
        assertTrue(distances.all { kotlin.math.abs(it - distances[0]) < eps }, "$distances")
    }

    @Test
    fun `ellipse is an inscribed octagon`() {
        val ellipse = Hitbox.Ellipse(2f, 1f)
        assertEquals(Hitbox.ELLIPSE_VERTICES, ellipse.vertices.size)
        assertEquals(2f, ellipse.halfWidth, eps)
        assertEquals(1f, ellipse.top, eps)
        // Площадь вписанного 8-угольника меньше πab, но близка к ней.
        assertTrue(ellipse.area < (PI * 2).toFloat() && ellipse.area > 0.85f * (PI * 2).toFloat())
    }

    @Test
    fun `every polygon is counter-clockwise`() {
        val shapes = listOf(
            Hitbox.Rect(1f, 2f),
            Hitbox.Ellipse(1f, 0.5f),
        ) + (Rotation.QUARTERS + Rotation(45f) + Rotation(-30f)).flatMap {
            listOf(Hitbox.Triangle(1f, 2f, it), Hitbox.Rect(1f, 2f, it), Hitbox.Ellipse(1f, 0.5f, it))
        }
        for (shape in shapes) assertTrue(shape.area > 0f, "$shape")
    }

    @Test
    fun `diagonal rotation keeps area and widens the bounds`() {
        val square = Hitbox.Rect(1f, 1f)
        val diamond = Hitbox.Rect(1f, 1f, Rotation(45f))
        assertEquals(square.area, diamond.area, eps)
        val halfDiagonal = sqrt(2f) / 2
        assertEquals(halfDiagonal, diamond.top, eps)
        assertEquals(halfDiagonal, diamond.bottom, eps)
        assertEquals(halfDiagonal, diamond.halfWidth, eps)

        val tilted = Hitbox.Triangle(1f, 1.5f, Rotation(45f))
        val upright = Hitbox.Triangle(1f, 1.5f)
        assertEquals(upright.area, tilted.area, eps)
        // Вершина (0, 1) после поворота на 45° против часовой стрелки уходит влево-вверх.
        val apex = tilted.vertices[2]
        assertEquals(-1f * sqrt(0.5f), apex.x, eps)
        assertEquals(sqrt(0.5f), apex.y, eps)
    }

    @Test
    fun `quarter turns are exact and wrap around`() {
        assertEquals(Vec2(-2f, 1f), Rotation.R90.apply(Vec2(1f, 2f)))
        assertEquals(Rotation.R90.apply(Vec2(1f, 2f)), Rotation(-270f).apply(Vec2(1f, 2f)))
        assertEquals(Vec2(1f, 2f), Rotation(720f).apply(Vec2(1f, 2f)))
    }

    @Test
    fun `ships use the shapes from the design`() {
        val rebel = ShipType.Rebel.stats.hitbox as Hitbox.Triangle
        assertEquals(Rotation.R0, rebel.rotation)
        assertEquals(rebel.base * sqrt(3f) / 2, rebel.height, eps)
        val glory = ShipType.Glory.stats.hitbox as Hitbox.Triangle
        assertEquals(Rotation.R180, glory.rotation)
        assertTrue(glory.height > glory.base)
    }
}
