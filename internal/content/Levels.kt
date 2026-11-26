package com.spaceace.game.content

import com.spaceace.game.model.NpcKind
import com.spaceace.game.npc.Shape
import com.spaceace.game.npc.SpawnEntry
import com.spaceace.game.npc.SpawnPattern
import kotlin.random.Random

/**
 * Этап забега: пул боссов (босс выбирается из него случайно) и противники, которые могут появиться:
 * вес выбора, размер группы и паттерн появления (строй, волна, засада из-за края).
 */
data class LevelSpec(val bosses: List<NpcKind>, val enemies: List<SpawnEntry>) {
    init {
        require(bosses.isNotEmpty()) { "level needs at least one boss" }
    }
}

object Levels {
    private val first = LevelSpec(
        bosses = listOf(Shard),
        // Вид может стоять в нескольких записях: разный паттерн появления — свой вес.
        enemies = listOf(
            SpawnEntry(Gravel, weight = 2f, group = 2..2, pattern = SpawnPattern.Formation(Shape.ROW)),
            SpawnEntry(Gravel, weight = 1f, group = 1..2, pattern = SpawnPattern.Ambush(speed = 3f)),
            SpawnEntry(Borer, weight = 1f, group = 3..3, pattern = SpawnPattern.Formation(Shape.WEDGE)),
            SpawnEntry(Borer, weight = 1f, group = 2..3, pattern = SpawnPattern.Formation(Shape.COLUMN)),
            SpawnEntry(Borer, weight = 1f, group = 2..3, pattern = SpawnPattern.Wave(interval = 0.9f)),
            SpawnEntry(Borer, weight = 1f, group = 1..2, pattern = SpawnPattern.Ambush(speed = 3.5f, minY = 0.6f, maxY = 0.9f)),
        ),
    )

    private val second = LevelSpec(
        bosses = listOf(Pr1177),
        enemies = listOf(
            SpawnEntry(Gravel, weight = 2f, group = 2..2, pattern = SpawnPattern.Formation(Shape.ROW)),
            SpawnEntry(Gravel, weight = 1f, group = 1..2, pattern = SpawnPattern.Ambush(speed = 3f)),
            SpawnEntry(Borer, weight = 1f, group = 3..3, pattern = SpawnPattern.Formation(Shape.WEDGE)),
            SpawnEntry(Borer, weight = 1f, group = 2..3, pattern = SpawnPattern.Formation(Shape.COLUMN)),
            SpawnEntry(Borer, weight = 1f, group = 2..3, pattern = SpawnPattern.Wave(interval = 0.9f)),
            SpawnEntry(Borer, weight = 1f, group = 1..2, pattern = SpawnPattern.Ambush(speed = 3.5f, minY = 0.6f, maxY = 0.9f)),
        ),
    )

    /** Пока босс один, второй этап повторяет первый (заглушка). */
    val ALL: List<LevelSpec> = listOf(first, first)

    /** Этапы дальше списка берут последний. */
    fun of(level: Int): LevelSpec = ALL[(level - 1).coerceIn(ALL.indices)]

    fun bossFor(level: Int, random: Random): NpcKind = of(level).bosses.random(random)
}

/** Все виды противников — для бестиария; союзники — отдельно. */
object Roster {
    val ALL: List<NpcKind> = listOf(Gravel, Borer, Shard, Pr1177)
    val ALLIES: List<NpcKind> = listOf(Drone)
}
