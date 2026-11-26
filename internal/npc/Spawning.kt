package com.spaceace.game.npc

import com.spaceace.game.Config
import com.spaceace.game.logic.MeteorSpawn
import com.spaceace.game.logic.difficulty
import com.spaceace.game.logic.weightedPick
import com.spaceace.game.model.Entry
import com.spaceace.game.model.Field
import com.spaceace.game.model.Meteor
import com.spaceace.game.model.NpcKind
import com.spaceace.game.model.Vec2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// --- Группы противников ---

/**
 * @property difficultyStep на сколько чаще появляются группы с каждым уровнем (доля базовой частоты).
 * @property groupSpacing расстояние между соседями в группе, м.
 * @property ambushMargin насколько далеко за боковым краем появляется противник из засады, м.
 */
data class NpcSpawnerSettings(
    val baseInterval: Float,
    val intervalJitter: Float,
    val difficultyStep: Float,
    val spawnHeight: Float,
    val groupSpacing: Float,
    val ambushMargin: Float,
) {
    init {
        require(baseInterval > 0f && intervalJitter in 0f..1f && difficultyStep >= 0f && spawnHeight >= 0f && groupSpacing > 0f) {
            "invalid npc spawner settings: $this"
        }
        require(ambushMargin >= 0f) { "invalid npc spawner settings: $this" }
    }
}

/** Фигура строя при появлении сверху. Дальше каждый действует по своему ИИ. */
enum class Shape {
    /** В ряд по горизонтали. */
    ROW,

    /** Клином: ведущий впереди (ниже), остальные расходятся назад-в-стороны. */
    WEDGE,

    /** Колонной: друг за другом по вертикали. */
    COLUMN,
}

/** Как появляется группа. */
sealed interface SpawnPattern {
    /** Вся группа сразу сверху строем [shape]. */
    data class Formation(val shape: Shape = Shape.ROW) : SpawnPattern

    /** Цепочкой: по одному сверху из одной точки через [interval] с. */
    data class Wave(val interval: Float) : SpawnPattern {
        init {
            require(interval > 0f) { "wave interval must be positive: $interval" }
        }
    }

    /**
     * Засада: группа появляется за одним боковым краем друг над другом и со скоростью [speed] влетает по горизонтали
     * на глубину [minDepth]..[maxDepth] (доли ширины поля от этого края); высота ведущего — [minY]..[maxY] (доли высоты).
     */
    data class Ambush(
        val speed: Float,
        val minDepth: Float = 0.2f,
        val maxDepth: Float = 0.5f,
        val minY: Float = 0.55f,
        val maxY: Float = 0.85f,
    ) : SpawnPattern {
        init {
            require(speed > 0f && minDepth in 0f..maxDepth && maxDepth <= 1f && minY in 0f..maxY && maxY <= 1f) { "invalid ambush: $this" }
        }
    }
}

/** Кто может появиться на уровне: вид, вес выбора, размер группы и как она появляется. */
data class SpawnEntry(
    val kind: NpcKind,
    val weight: Float,
    val group: IntRange = 1..1,
    val pattern: SpawnPattern = SpawnPattern.Formation(),
) {
    init {
        require(weight >= 0f && group.first >= 1) { "invalid spawn entry: $this" }
    }
}

/** [entry] — точка входа для засады; null — обычное появление сверху. */
data class NpcSpawn(val kind: NpcKind, val position: Vec2, val entry: Entry? = null)

/**
 * Появление групп противников; первая группа — через полный интервал после начала уровня. Группа выбирается
 * случайно по весам записей; волна раскладывается во времени — остаток ждёт в очереди.
 */
class NpcSpawner(private val settings: NpcSpawnerSettings, private val field: Field, private val random: Random) {
    private var timer = settings.baseInterval
    private val pending = ArrayList<Delayed>()

    private class Delayed(var delay: Float, val spawn: NpcSpawn)

    fun reset() {
        timer = settings.baseInterval
        pending.clear()
    }

    fun update(dt: Float, level: Int, entries: List<SpawnEntry>): List<NpcSpawn> {
        val due = releaseDue(dt)
        timer -= dt
        if (timer > 0f) return due
        val jitter = 1f + (random.nextFloat() * 2f - 1f) * settings.intervalJitter
        timer += settings.baseInterval / difficulty(level, settings.difficultyStep) * jitter
        if (entries.none { it.weight > 0f }) return due
        val entry = weightedPick(entries.associateWith { it.weight }, random)
        val count = random.nextInt(entry.group.first, entry.group.last + 1)
        return due + when (val pattern = entry.pattern) {
            is SpawnPattern.Formation -> formation(entry.kind, count, pattern.shape)
            is SpawnPattern.Wave -> wave(entry.kind, count, pattern.interval)
            is SpawnPattern.Ambush -> ambush(entry.kind, count, pattern)
        }
    }

    private fun releaseDue(dt: Float): List<NpcSpawn> {
        if (pending.isEmpty()) return emptyList()
        pending.forEach { it.delay -= dt }
        val due = pending.filter { it.delay <= 0f }
        pending.removeAll(due.toSet())
        return due.map { it.spawn }
    }

    private fun formation(kind: NpcKind, count: Int, shape: Shape): List<NpcSpawn> {
        val hitbox = kind.body.hitbox
        val offsets = formationOffsets(shape, count, settings.groupSpacing)
        val left = offsets.minOf { it.x }
        val right = offsets.maxOf { it.x }
        val room = (field.width - 2 * hitbox.halfWidth - (right - left)).coerceAtLeast(0f)
        val anchorX = hitbox.halfWidth - left + random.nextFloat() * room
        val anchorY = field.height + hitbox.bottom + random.nextFloat() * settings.spawnHeight
        return offsets.map { NpcSpawn(kind, Vec2(anchorX + it.x, anchorY + it.y)) }
    }

    private fun wave(kind: NpcKind, count: Int, interval: Float): List<NpcSpawn> {
        val hitbox = kind.body.hitbox
        val x = hitbox.halfWidth + random.nextFloat() * (field.width - 2 * hitbox.halfWidth)
        val y = field.height + hitbox.bottom
        val spawn = NpcSpawn(kind, Vec2(x, y))
        for (i in 1 until count) pending += Delayed(i * interval, spawn)
        return listOf(spawn)
    }

    private fun ambush(kind: NpcKind, count: Int, ambush: SpawnPattern.Ambush): List<NpcSpawn> {
        val halfWidth = kind.body.hitbox.halfWidth
        val fromLeft = random.nextBoolean()
        val depth = (ambush.minDepth + random.nextFloat() * (ambush.maxDepth - ambush.minDepth)) * field.width
        val outside = halfWidth + settings.ambushMargin
        val startX = if (fromLeft) -outside else field.width + outside
        // Цель — не ближе полукорпуса к стене, иначе противник «долетел» бы, ещё касаясь её.
        val targetX = (if (fromLeft) depth else field.width - depth).coerceIn(halfWidth, field.width - halfWidth)
        val topY = field.height * (ambush.minY + random.nextFloat() * (ambush.maxY - ambush.minY))
        return List(count) { i ->
            val y = topY - i * settings.groupSpacing
            NpcSpawn(kind, Vec2(startX, y), Entry(Vec2(targetX, y), ambush.speed))
        }
    }
}

/**
 * Смещения членов строя от ведущего (м, Y вверх). Ведущий — (0, 0); строй летит вниз, поэтому «позади» — выше.
 */
fun formationOffsets(shape: Shape, count: Int, spacing: Float): List<Vec2> = List(count) { i ->
    when (shape) {
        Shape.ROW -> Vec2(i * spacing, 0f)
        Shape.COLUMN -> Vec2(0f, i * spacing)
        Shape.WEDGE -> {
            val rank = (i + 1) / 2
            val side = if (i % 2 == 1) -1f else 1f
            Vec2(side * rank * spacing, rank * spacing * Config.Enemy.WEDGE_DEPTH)
        }
    }
}

// --- Метеориты из-за боковых краёв ---

/** Метеориты из-за боковых краёв (окружение фазы босса): появляются за стеной и летят внутрь и вниз. */
class SideMeteorSpawner(private val field: Field, private val random: Random) {
    private var timer = 0f

    fun reset() {
        timer = 0f
    }

    fun update(dt: Float, settings: SideMeteorSettings): List<MeteorSpawn> {
        timer -= dt
        if (timer > 0f) return emptyList()
        timer += settings.interval * (1f + (random.nextFloat() * 2f - 1f) * settings.intervalJitter)
        return listOf(spawn(settings))
    }

    private fun spawn(settings: SideMeteorSettings): MeteorSpawn {
        val size = weightedPick(settings.sizeWeights, random)
        val halfWidth = size.stats.hitbox.halfWidth
        val fromLeft = random.nextBoolean()
        val inward = if (fromLeft) 1f else -1f
        val angle = lerp(settings.minAngle, settings.maxAngle)
        val speed = lerp(settings.minSpeed, settings.maxSpeed)
        val x = if (fromLeft) -halfWidth else field.width + halfWidth
        val y = field.height * lerp(settings.minY, settings.maxY)
        return MeteorSpawn(size, Vec2(x, y), Vec2(inward * cos(angle) * speed, -sin(angle) * speed))
    }

    private fun lerp(from: Float, to: Float) = from + random.nextFloat() * (to - from)
}

/** Влетающий метеорит целиком на поле: дальше стены снова его держат. */
fun hasEntered(meteor: Meteor, field: Field): Boolean {
    val halfWidth = meteor.hitbox.halfWidth
    return meteor.position.x - halfWidth >= 0f && meteor.position.x + halfWidth <= field.width
}
