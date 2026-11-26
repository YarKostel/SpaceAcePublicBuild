package com.spaceace.game.ui

import com.spaceace.game.Config
import com.spaceace.game.content.Borer
import com.spaceace.game.content.Drone
import com.spaceace.game.content.Gravel
import com.spaceace.game.content.Roster
import com.spaceace.game.content.Shard
import com.spaceace.game.model.ShipType
import com.spaceace.game.state.GameState
import com.spaceace.game.state.MetaState
import com.spaceace.game.state.ScreenState
import com.spaceace.game.state.Wallet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Тесты не привязаны к числу кораблей: проходят по всем положениям слайдера, сколько бы кораблей ни было. */
class HangarCarouselTest {
    private val all = ShipType.ALL
    private val positions = 0..HangarCarousel.lastFirst

    private fun hangar(meta: MetaState = MetaState(Wallet(10_000))): GameState =
        GameState(meta).also { assertTrue(it.openHangar()) }

    private fun shipActions(buttons: List<UiButton>): List<ShipType> = buttons.mapNotNull {
        when (val action = it.action) {
            is UiAction.SelectShip -> action.ship
            is UiAction.BuyShip -> action.ship
            else -> null
        }
    }

    private fun tap(state: GameState, view: UiView, action: UiAction): UiView {
        val button = assertNotNull(layout(state, view).firstOrNull { it.action == action }, "no button for $action")
        val hit = assertNotNull(buttonAt(layout(state, view), button.rect.centerX, button.rect.centerY), "$action is disabled")
        return state.perform(hit.action, view)
    }

    @Test
    fun `window shows consecutive ships in order and never more than fit`() {
        for (first in positions) {
            val window = HangarCarousel.window(first)
            assertEquals(HangarCarousel.visible, window.size)
            assertEquals(all.subList(first, first + window.size), window.map { it.value })
            assertEquals((first until first + window.size).toList(), window.map { it.index })
        }
        assertEquals(minOf(Config.Ui.HANGAR_VISIBLE_CARDS, all.size), HangarCarousel.visible)
    }

    @Test
    fun `scroll position is clamped to the list`() {
        assertEquals(0, HangarCarousel.clamp(-5))
        assertEquals(HangarCarousel.lastFirst, HangarCarousel.clamp(all.size + 10))
        assertEquals(HangarCarousel.window(0), HangarCarousel.window(-1))
    }

    @Test
    fun `window jumps the least to show a given ship`() {
        val visible = HangarCarousel.visible
        for (index in all.indices) for (current in positions) {
            val first = HangarCarousel.showing(index, current)
            assertTrue(index in first until first + visible, "ship $index hidden in window $first")
            if (index in current until current + visible) assertEquals(current, first, "already visible, must not move")
        }
    }

    @Test
    fun `short drags are taps, long ones page`() {
        val min = Config.Ui.SWIPE_MIN_DISTANCE
        assertEquals(0, HangarCarousel.swipe(min - 1f))
        assertEquals(0, HangarCarousel.swipe(-(min - 1f)))
        assertEquals(1, HangarCarousel.swipe(-min), "finger to the left shows the next ships")
        assertEquals(-1, HangarCarousel.swipe(min))
    }

    @Test
    fun `card buttons follow the visible ships at every position`() {
        val state = hangar()
        for (first in positions) {
            val buttons = layout(state, UiView(hangarFirst = first))
            assertEquals(HangarCarousel.window(first).map { it.value }, shipActions(buttons))
        }
    }

    @Test
    fun `arrows exist only when ships do not fit and switch off at the ends`() {
        val state = hangar()
        for (first in positions) {
            val buttons = layout(state, UiView(hangarFirst = first))
            val left = buttons.firstOrNull { it.action == UiAction.ScrollHangar(-1) }
            val right = buttons.firstOrNull { it.action == UiAction.ScrollHangar(1) }
            assertEquals(HangarCarousel.scrollable, left != null && right != null)
            if (left != null && right != null) {
                assertEquals(first > 0, left.enabled, "left arrow at $first")
                assertEquals(first < HangarCarousel.lastFirst, right.enabled, "right arrow at $first")
            }
        }
    }

    @Test
    fun `hangar fits the screen and nothing overlaps at every position`() {
        val state = hangar()
        val w = Config.Display.VIRTUAL_WIDTH
        val h = Config.Display.VIRTUAL_HEIGHT
        for (first in positions) {
            val buttons = layout(state, UiView(hangarFirst = first))
            val rects = buttons.map { it.rect } + (0 until HangarCarousel.visible).map(::cardRect)
            for (r in rects) assertTrue(r.x >= 0f && r.y >= 0f && r.x + r.width <= w && r.y + r.height <= h, "$r off screen at $first")
            for (i in buttons.indices) for (j in i + 1 until buttons.size) {
                val a = buttons[i].rect
                val b = buttons[j].rect
                val overlap = a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height
                assertFalse(overlap, "${buttons[i].label} overlaps ${buttons[j].label} at $first")
            }
        }
    }

    @Test
    fun `arrows page through every ship and stop at the ends`() {
        val state = hangar()
        var view = UiView()
        val seen = HangarCarousel.window(view.hangarFirst).map { it.value }.toMutableSet()
        repeat(HangarCarousel.lastFirst) {
            view = tap(state, view, UiAction.ScrollHangar(1))
            seen += HangarCarousel.window(view.hangarFirst).map { it.value }
        }
        assertEquals(all.toSet(), seen)
        assertEquals(HangarCarousel.lastFirst, view.hangarFirst)
        assertEquals(view, state.perform(UiAction.ScrollHangar(1), view), "cannot scroll past the last ship")
        repeat(HangarCarousel.lastFirst) { view = tap(state, view, UiAction.ScrollHangar(-1)) }
        assertEquals(0, view.hangarFirst)
    }

    @Test
    fun `last ship can be bought and selected after scrolling`() {
        val state = hangar()
        val last = all.last()
        var view = UiView()
        repeat(HangarCarousel.lastFirst) { view = state.perform(UiAction.ScrollHangar(1), view) }
        if (!state.meta.owns(last)) view = tap(state, view, UiAction.BuyShip(last))
        assertEquals(HangarCarousel.lastFirst, view.hangarFirst, "buying keeps the slider in place")
        tap(state, view, UiAction.SelectShip(last))
        assertEquals(ScreenState.LOADOUT, state.screen)
        assertEquals(last, state.meta.selectedShip)
    }

    @Test
    fun `opening the hangar shows the selected ship`() {
        val last = all.last()
        val state = GameState(MetaState(Wallet(0), ships = all.toSet(), selectedShip = last))
        val view = state.perform(UiAction.OpenHangar, UiView())
        assertEquals(ScreenState.HANGAR, state.screen)
        assertTrue(HangarCarousel.window(view.hangarFirst).any { it.value == last })
    }

    @Test
    fun `returning from loadout keeps the slider where the player left it`() {
        val state = hangar(MetaState(Wallet(0), ships = all.toSet()))
        var view = UiView()
        repeat(HangarCarousel.lastFirst) { view = state.perform(UiAction.ScrollHangar(1), view) }
        val ship = HangarCarousel.window(view.hangarFirst).last().value
        view = state.perform(UiAction.SelectShip(ship), view)
        view = state.perform(UiAction.OpenHangar, view)
        assertEquals(HangarCarousel.lastFirst, view.hangarFirst)
    }
}

class CarouselTest {
    @Test
    fun `long list scrolls window by window`() {
        val carousel = Carousel(total = 4, visible = 2)
        assertTrue(carousel.scrollable)
        assertEquals(2, carousel.lastFirst)
        assertEquals(0..1, carousel.range(0))
        assertEquals(2..3, carousel.range(2))
        assertEquals(2..3, carousel.range(99), "clamped to the end")
        assertEquals(0..1, carousel.range(-3), "clamped to the start")
    }

    @Test
    fun `short list fits without scrolling`() {
        val carousel = Carousel(total = 1, visible = 2)
        assertFalse(carousel.scrollable)
        assertEquals(1, carousel.shown)
        assertEquals(0..0, carousel.range(5))
        assertTrue(Carousel(total = 0, visible = 2).range(0).isEmpty())
    }

    @Test
    fun `showing moves the window the least`() {
        val carousel = Carousel(total = 5, visible = 2)
        assertEquals(1, carousel.showing(index = 2, current = 0))
        assertEquals(3, carousel.showing(index = 4, current = 0))
        assertEquals(1, carousel.showing(index = 1, current = 3))
        assertEquals(2, carousel.showing(index = 3, current = 2), "already visible")
    }

    @Test
    fun `swipe needs a long enough drag`() {
        val min = Config.Ui.SWIPE_MIN_DISTANCE
        assertEquals(0, swipeStep(min / 2))
        assertEquals(1, swipeStep(-min))
        assertEquals(-1, swipeStep(min))
    }
}

/** Проходит по всем кораблям и всем положениям слайдера — не зависит от того, сколько подвесов у кого. */
class StoreCarouselTest {
    private fun loadout(ship: ShipType, meta: MetaState = MetaState(Wallet(10_000), ships = ShipType.ALL.toSet())): Pair<GameState, UiView> {
        val state = GameState(meta)
        state.openHangar()
        val view = state.perform(UiAction.SelectShip(ship), UiView())
        assertEquals(ScreenState.LOADOUT, state.screen)
        return state to view
    }

    private fun picks(buttons: List<UiButton>) = buttons.mapNotNull { (it.action as? UiAction.PickStore)?.index }

    private fun overlaps(a: Rect, b: Rect) = a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height

    @Test
    fun `visible store buttons follow the slider for every ship`() {
        for (ship in ShipType.ALL) {
            val (state, _) = loadout(ship)
            val carousel = storeCarousel(ship)
            for (first in 0..carousel.lastFirst) {
                val buttons = layout(state, UiView(storeFirst = first))
                assertEquals(carousel.range(first).toList(), picks(buttons), "$ship at $first")
            }
        }
    }

    @Test
    fun `arrows exist only for long lists and switch off at the ends`() {
        for (ship in ShipType.ALL) {
            val (state, _) = loadout(ship)
            val carousel = storeCarousel(ship)
            for (first in 0..carousel.lastFirst) {
                val buttons = layout(state, UiView(storeFirst = first))
                val up = buttons.firstOrNull { it.action == UiAction.ScrollStores(-1) }
                val down = buttons.firstOrNull { it.action == UiAction.ScrollStores(1) }
                assertEquals(carousel.scrollable, up != null && down != null, "$ship")
                if (up != null && down != null) {
                    assertEquals(first > 0, up.enabled, "$ship up at $first")
                    assertEquals(first < carousel.lastFirst, down.enabled, "$ship down at $first")
                }
            }
        }
    }

    @Test
    fun `stores never cover the inventory, the description or each other`() {
        val ui = Config.Ui
        val w = Config.Display.VIRTUAL_WIDTH
        val inventory = (0 until Config.Inventory.SLOTS).map { centeredSlotRect(it, ui.LOADOUT_INVENTORY_Y) } +
            Rect(0f, ui.LOADOUT_INVENTORY_Y + ui.SLOT_SIZE, w, ui.ITEM_LINE_HEIGHT) // подпись INVENTORY
        val description = Rect((w - ui.DESCRIPTION_WIDTH) / 2, ui.DESCRIPTION_Y, ui.DESCRIPTION_WIDTH, ui.DESCRIPTION_HEIGHT)
        for (ship in ShipType.ALL) {
            val (state, _) = loadout(ship)
            for (first in 0..storeCarousel(ship).lastFirst) {
                val buttons = layout(state, UiView(storeFirst = first))
                for (b in buttons) {
                    val r = b.rect
                    assertTrue(r.x >= 0f && r.x + r.width <= w, "${b.label} off screen")
                    for (fixed in inventory + description) assertFalse(overlaps(r, fixed), "${b.label} covers $fixed ($ship at $first)")
                }
                for (i in buttons.indices) for (j in i + 1 until buttons.size) {
                    assertFalse(overlaps(buttons[i].rect, buttons[j].rect), "${buttons[i].label} / ${buttons[j].label} ($ship at $first)")
                }
            }
        }
    }

    @Test
    fun `at most one store is highlighted even with identical options`() {
        for (ship in ShipType.ALL) {
            val (state, _) = loadout(ship)
            val highlighted = (0..storeCarousel(ship).lastFirst)
                .flatMap { first -> layout(state, UiView(storeFirst = first)).filter { it.selected } }
                .map { (it.action as UiAction.PickStore).index }
                .toSet()
            assertEquals(1, highlighted.size, "$ship highlights $highlighted")
        }
    }

    @Test
    fun `entering the loadout shows the chosen store`() {
        for (ship in ShipType.ALL) {
            val options = ship.stats.stores
            val lastType = options.last().type
            val meta = MetaState(Wallet(0), ships = ShipType.ALL.toSet(), stores = setOf(lastType), selectedStores = mapOf(ship to lastType))
            val (state, view) = loadout(ship, meta)
            val chosen = options.indexOf(state.meta.storeFor(ship))
            assertTrue(chosen in storeCarousel(ship).range(view.storeFirst), "$ship: chosen $chosen hidden at ${view.storeFirst}")
        }
    }

    @Test
    fun `scrolling is clamped and does not leak to another ship`() {
        val ship = ShipType.ALL.firstOrNull { storeCarousel(it).scrollable } ?: return
        val other = ShipType.ALL.first { it != ship }
        val (state, start) = loadout(ship)
        val carousel = storeCarousel(ship)
        var view = start
        repeat(carousel.total + 2) { view = state.perform(UiAction.ScrollStores(1), view) }
        assertEquals(carousel.lastFirst, view.storeFirst)
        repeat(carousel.total + 2) { view = state.perform(UiAction.ScrollStores(-1), view) }
        assertEquals(0, view.storeFirst)

        repeat(carousel.lastFirst) { view = state.perform(UiAction.ScrollStores(1), view) }
        view = state.perform(UiAction.OpenHangar, view)
        view = state.perform(UiAction.SelectShip(other), view)
        val otherChosen = other.stats.stores.indexOf(state.meta.storeFor(other))
        assertTrue(otherChosen in storeCarousel(other).range(view.storeFirst))
    }

    @Test
    fun `a store reached by scrolling can be picked`() {
        // Нужен корабль с длинным списком разных подвесов: одинаковые варианты по выбору неразличимы.
        val ship = ShipType.ALL.firstOrNull { s ->
            storeCarousel(s).scrollable && s.stats.stores.map { it.type }.toSet().size == s.stats.stores.size
        } ?: return
        val (state, start) = loadout(ship)
        var view = start
        repeat(storeCarousel(ship).lastFirst) { view = state.perform(UiAction.ScrollStores(1), view) }
        val last = ship.stats.stores.lastIndex
        val button = assertNotNull(layout(state, view).firstOrNull { it.action == UiAction.PickStore(last) })
        state.perform(assertNotNull(buttonAt(layout(state, view), button.rect.centerX, button.rect.centerY)).action, view)
        assertEquals(ship.stats.stores.last(), state.meta.storeFor(ship))
    }
}

class BestiaryTest {
    private fun tap(state: GameState, view: UiView, action: UiAction): UiView {
        val button = assertNotNull(layout(state, view).firstOrNull { it.action == action && it.enabled }, "no enabled button for $action")
        val hit = assertNotNull(buttonAt(layout(state, view), button.rect.centerX, button.rect.centerY))
        return state.perform(hit.action, view)
    }

    private fun inBestiary(seen: Set<String>): Pair<GameState, UiView> {
        val state = GameState(MetaState(seen = seen))
        state.openHangar()
        return state to tap(state, UiView(), UiAction.OpenBestiary)
    }

    @Test
    fun `hangar opens the bestiary and back returns`() {
        val (state, view) = inBestiary(emptySet())
        assertEquals(ScreenState.BESTIARY, state.screen)
        tap(state, view, UiAction.OpenHangar)
        assertEquals(ScreenState.HANGAR, state.screen)
    }

    @Test
    fun `grid shows enemies, bosses and allies, only met ones open`() {
        assertEquals(Roster.ALL + Roster.ALLIES, Bestiary.entries)
        val (state, view) = inBestiary(setOf(Gravel.id, Drone.id))
        val cards = layout(state, view).filter { it.action is UiAction.OpenEntry }
        assertEquals(Bestiary.entries.map { UiAction.OpenEntry(it.id) }, cards.map { it.action })
        assertEquals(setOf(Gravel.id, Drone.id), cards.filter { it.enabled }.map { (it.action as UiAction.OpenEntry).kindId }.toSet())

        val opened = tap(state, view, UiAction.OpenEntry(Gravel.id))
        assertEquals(Gravel.id, opened.bestiaryEntry)
        assertEquals(listOf(UiAction.CloseEntry), layout(state, opened).map { it.action })
        assertNull(tap(state, opened, UiAction.CloseEntry).bestiaryEntry)
        assertNull(state.perform(UiAction.OpenEntry(Shard.id), view).bestiaryEntry, "a locked card cannot be opened")
    }

    @Test
    fun `bestiary opens from the hangar after quitting a run`() {
        val state = GameState()
        state.startRun()
        var view = UiView()
        view = tap(state, view, UiAction.Pause)
        view = tap(state, view, UiAction.OpenHangar)
        assertEquals(ScreenState.HANGAR, state.screen)
        tap(state, view, UiAction.OpenBestiary)
        assertEquals(ScreenState.BESTIARY, state.screen)
    }

    @Test
    fun `roles are labelled`() {
        assertEquals("BOSS", Bestiary.roleLabel(Shard))
        assertEquals("ENEMY", Bestiary.roleLabel(Borer))
        assertEquals("ALLY", Bestiary.roleLabel(Drone))
    }

    @Test
    fun `cells form a grid of three columns without overlaps and above the back button`() {
        val cells = (0 until Config.Ui.BESTIARY_COLUMNS * Config.Ui.BESTIARY_ROWS).map(::bestiaryCellRect)
        for (i in cells.indices) for (j in i + 1 until cells.size) {
            val a = cells[i]
            val b = cells[j]
            assertFalse(a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height, "$a vs $b")
        }
        assertTrue(cells.all { it.x >= 0f && it.x + it.width <= Config.Display.VIRTUAL_WIDTH })
        assertTrue(cells.minOf { it.y } > Config.Ui.BOTTOM_BUTTON_Y + Config.Ui.BUTTON_HEIGHT)
        assertEquals(cells[0].y, cells[2].y)
        assertTrue(cells[3].y < cells[0].y)
    }

    @Test
    fun `leaving the bestiary forgets the open card`() {
        val (state, view) = inBestiary(setOf(Gravel.id))
        val opened = tap(state, view, UiAction.OpenEntry(Gravel.id))
        val back = state.perform(UiAction.OpenHangar, opened)
        assertEquals(ScreenState.HANGAR, state.screen)
        assertNull(back.bestiaryEntry)
    }
}

class TextWrapTest {
    /** Моноширинная «линейка»: каждый символ — 10 единиц. */
    private val measure = { text: String -> text.length * 10f }

    @Test
    fun `short paragraph stays on one line`() {
        assertEquals(listOf("Heavy cargo starship."), wrapParagraphs(listOf("Heavy cargo starship."), 300f, measure))
    }

    @Test
    fun `long paragraph breaks between words and keeps them in order`() {
        val text = "Slow and unwieldy, it boasts impressive durability"
        val lines = wrapParagraphs(listOf(text), 200f, measure)
        assertTrue(lines.size > 1)
        assertTrue(lines.all { measure(it) <= 200f }, "$lines")
        assertEquals(text, lines.joinToString(" "))
    }

    @Test
    fun `each paragraph starts on a new line`() {
        val lines = wrapParagraphs(listOf("one two", "three"), 1000f, measure)
        assertEquals(listOf("one two", "three"), lines)
    }

    @Test
    fun `a word wider than the line is not cut`() {
        val lines = wrapParagraphs(listOf("a supercalifragilistic b"), 100f, measure)
        assertEquals(listOf("a", "supercalifragilistic", "b"), lines)
    }

    @Test
    fun `extra spaces and empty paragraphs give no empty lines`() {
        assertEquals(listOf("a b"), wrapParagraphs(listOf("  a   b ", ""), 1000f, measure))
    }

    @Test
    fun `fit keeps the largest scale that fits the box`() {
        // 4 строки по 20 символов; при масштабе 1 строка 20 высотой — 80 из 100, влезает сразу.
        val paragraphs = List(4) { "x".repeat(20) }
        val fitted = fitText(paragraphs, width = 200f, height = 100f, scales = listOf(1f, 0.5f), lineHeight = 20f, measure = measure)
        assertEquals(1f, fitted.scale)
        assertEquals(4, fitted.lines.size)
    }

    @Test
    fun `fit shrinks the font when text is too tall`() {
        val paragraphs = listOf("aaaa bbbb cccc dddd eeee ffff")
        // При масштабе 2 в строку шириной 100 влезает 5 символов → 6 строк × 40 = 240 > 100.
        // При масштабе 1 — 10 символов → 3 строки × 20 = 60 ≤ 100.
        val fitted = fitText(paragraphs, width = 100f, height = 100f, scales = listOf(2f, 1.5f, 1f), lineHeight = 20f, measure = measure)
        assertEquals(1f, fitted.scale)
        assertTrue(fitted.lines.size * 20f * fitted.scale <= 100f)
    }

    @Test
    fun `fit falls back to the smallest scale when nothing fits`() {
        val fitted = fitText(listOf("word ".repeat(50)), 50f, 10f, listOf(1f, 0.8f), 20f, measure)
        assertEquals(0.8f, fitted.scale)
        assertTrue(fitted.lines.isNotEmpty())
    }

    @Test
    fun `scale steps go from max to min and include both ends`() {
        val steps = scaleSteps(1.5f, 0.9f, 0.1f)
        assertEquals(1.5f, steps.first())
        assertEquals(0.9f, steps.last())
        assertEquals(7, steps.size, "$steps")
        assertTrue(steps.zipWithNext().all { (a, b) -> a > b })
        assertEquals(listOf(1f), scaleSteps(1f, 1f, 0.1f))
    }

    @Test
    fun `every ship description fits its box with a rough monospace estimate`() {
        // Грубая оценка встроенного шрифта при масштабе 1: ~8 px на символ, строка ~18 px.
        val ui = Config.Ui
        for (ship in ShipType.ALL) {
            val fitted = fitText(
                ship.description(),
                ui.DESCRIPTION_WIDTH - 2 * ui.DESCRIPTION_PADDING,
                ui.DESCRIPTION_HEIGHT - 2 * ui.DESCRIPTION_PADDING,
                scaleSteps(ui.SMALL_SCALE, ui.DESCRIPTION_MIN_SCALE, ui.DESCRIPTION_SCALE_STEP),
                lineHeight = 18f,
                measure = { it.length * 8f },
            )
            val height = fitted.lines.size * 18f * fitted.scale
            assertTrue(height <= ui.DESCRIPTION_HEIGHT - 2 * ui.DESCRIPTION_PADDING, "$ship description is too long: ${fitted.lines}")
        }
    }
}
