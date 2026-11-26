package com.spaceace.game.ui

import com.spaceace.game.Config
import com.spaceace.game.content.Roster
import com.spaceace.game.model.NpcKind
import com.spaceace.game.model.Role
import com.spaceace.game.model.ShipType
import kotlin.math.abs

// --- Слайдеры ---

/**
 * Окно из [visible] подряд идущих элементов списка длиной [total]; положение окна — индекс первого видимого.
 * Общая логика слайдеров меню (корабли в ангаре, подвесы в снаряжении), без LibGDX.
 */
data class Carousel(val total: Int, val visible: Int) {
    init {
        require(total >= 0 && visible >= 1) { "invalid carousel: $this" }
    }

    /** Сколько элементов реально на экране (меньше [visible], если список короче). */
    val shown: Int get() = minOf(visible, total)

    val lastFirst: Int get() = (total - shown).coerceAtLeast(0)

    /** Нужен ли слайдер вообще: элементов больше, чем помещается. */
    val scrollable: Boolean get() = total > visible

    fun clamp(first: Int): Int = first.coerceIn(0, lastFirst)

    /** Индексы видимых элементов. */
    fun range(first: Int): IntRange = clamp(first).let { it until it + shown }

    /** Ближайшее к [current] положение, при котором виден элемент [index] (чтобы выбранное не пряталось). */
    fun showing(index: Int, current: Int): Int = clamp(
        when {
            index < current -> index
            index >= current + shown -> index - shown + 1
            else -> current
        },
    )
}

/**
 * Шаг листания по свайпу: [travel] — сдвиг пальца в сторону «назад» со знаком минус.
 * Короче [minDistance] — не свайп, а неточное касание.
 */
fun swipeStep(travel: Float, minDistance: Float = Config.Ui.SWIPE_MIN_DISTANCE): Int = when {
    abs(travel) < minDistance -> 0
    travel < 0f -> 1
    else -> -1
}

/**
 * Слайдер ангара: на экране одновременно [Config.Ui.HANGAR_VISIBLE_CARDS] карточек полного размера,
 * остальные корабли — за стрелками или свайпом.
 */
object HangarCarousel {
    private val carousel: Carousel get() = Carousel(ShipType.ALL.size, Config.Ui.HANGAR_VISIBLE_CARDS)

    val visible: Int get() = carousel.shown
    val lastFirst: Int get() = carousel.lastFirst
    val scrollable: Boolean get() = carousel.scrollable

    fun clamp(first: Int): Int = carousel.clamp(first)

    /** Видимые корабли с их индексами в [ShipType.ALL]; позиция в списке — место карточки на экране. */
    fun window(first: Int): List<IndexedValue<ShipType>> = carousel.range(first).map { IndexedValue(it, ShipType.ALL[it]) }

    fun showing(index: Int, current: Int): Int = carousel.showing(index, current)

    /** Палец влево ([dx] < 0) — следующие корабли (+1), вправо — предыдущие (−1). */
    fun swipe(dx: Float, minDistance: Float = Config.Ui.SWIPE_MIN_DISTANCE): Int = swipeStep(dx, minDistance)
}

/** Слайдер слотов сохранения: карточки того же размера и на тех же местах, что в ангаре. */
fun savesCarousel(slots: Int) = Carousel(slots, Config.Ui.HANGAR_VISIBLE_CARDS)

/** Слайдер подвесов в меню снаряжения: у каждого корабля свой список вариантов. */
fun storeCarousel(ship: ShipType) = Carousel(ship.stats.stores.size, Config.Ui.LOADOUT_VISIBLE_STORES)

// --- Бестиарий ---

/**
 * Бестиарий: карточки врагов и боссов, затем союзников. Сетка BESTIARY_COLUMNS колонок; видно BESTIARY_ROWS рядов,
 * остальные — слайдером по рядам (тот же [Carousel], что в ангаре).
 */
object Bestiary {
    val entries: List<NpcKind> get() = Roster.ALL + Roster.ALLIES

    private val columns get() = Config.Ui.BESTIARY_COLUMNS

    val carousel: Carousel get() = Carousel((entries.size + columns - 1) / columns, Config.Ui.BESTIARY_ROWS)

    /** Видимые карточки: вид и место на экране (номер ячейки в видимом окне, по рядам слева направо). */
    fun window(firstRow: Int): List<IndexedValue<NpcKind>> {
        val rows = carousel.range(firstRow)
        val from = rows.first * columns
        val to = minOf((rows.last + 1) * columns, entries.size)
        return (from until to).map { IndexedValue(it - from, entries[it]) }
    }

    fun find(id: String): NpcKind? = entries.firstOrNull { it.id == id }

    fun roleLabel(kind: NpcKind): String = when {
        kind in Roster.ALLIES -> "ALLY"
        kind.role == Role.BOSS -> "BOSS"
        else -> "ENEMY"
    }
}

/** Ячейка сетки бестиария по месту в видимом окне. */
fun bestiaryCellRect(place: Int): Rect {
    val ui = Config.Ui
    val column = place % ui.BESTIARY_COLUMNS
    val row = place / ui.BESTIARY_COLUMNS
    val gridWidth = ui.BESTIARY_COLUMNS * ui.BESTIARY_CELL_WIDTH + (ui.BESTIARY_COLUMNS - 1) * ui.BESTIARY_GAP
    val x = (Config.Display.VIRTUAL_WIDTH - gridWidth) / 2 + column * (ui.BESTIARY_CELL_WIDTH + ui.BESTIARY_GAP)
    val y = ui.BESTIARY_TOP_Y - (row + 1) * ui.BESTIARY_CELL_HEIGHT - row * ui.BESTIARY_GAP
    return Rect(x, y, ui.BESTIARY_CELL_WIDTH, ui.BESTIARY_CELL_HEIGHT)
}

// --- Перенос текста ---

/** Текст, разбитый на строки, и масштаб шрифта, при котором он влезает в рамку. */
data class WrappedText(val scale: Float, val lines: List<String>)

/**
 * Разбивает абзацы на строки не шире [maxWidth]. Ширину строки меряет [measure] (снаружи — шрифт LibGDX,
 * в тестах — простая функция). Слово шире строки не режется, а встаёт отдельной строкой.
 */
fun wrapParagraphs(paragraphs: List<String>, maxWidth: Float, measure: (String) -> Float): List<String> {
    val lines = ArrayList<String>()
    for (paragraph in paragraphs) {
        val words = paragraph.split(' ').filter { it.isNotEmpty() }
        var line = ""
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && measure(candidate) > maxWidth) {
                lines += line
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) lines += line
    }
    return lines
}

/**
 * Наибольший масштаб из [scales] (по убыванию), при котором перенесённый текст влезает в [width] × [height].
 * [measure] и [lineHeight] заданы при масштабе 1: ширина текста растёт с масштабом линейно.
 * Если не влезает даже при последнем масштабе — возвращается он (лучше мелко, чем мимо рамки по ширине).
 */
fun fitText(
    paragraphs: List<String>,
    width: Float,
    height: Float,
    scales: List<Float>,
    lineHeight: Float,
    measure: (String) -> Float,
): WrappedText {
    require(scales.isNotEmpty()) { "need at least one scale" }
    var fitted = WrappedText(scales.last(), emptyList())
    for (scale in scales) {
        val lines = wrapParagraphs(paragraphs, width / scale, measure)
        fitted = WrappedText(scale, lines)
        if (lines.size * lineHeight * scale <= height) return fitted
    }
    return fitted
}

/** Масштабы от [max] до [min] с шагом [step] — кандидаты для [fitText]. */
fun scaleSteps(max: Float, min: Float, step: Float): List<Float> {
    require(max >= min && step > 0f) { "invalid scale range: $max..$min by $step" }
    return buildList {
        var scale = max
        // Допуск — от накопления ошибки float при вычитании шага.
        while (scale > min + SCALE_EPSILON) {
            add(scale)
            scale -= step
        }
        add(min)
    }
}

private const val SCALE_EPSILON = 1e-4f
