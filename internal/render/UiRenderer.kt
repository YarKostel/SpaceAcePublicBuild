package com.spaceace.game.render

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Camera
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.glutils.ShapeRenderer
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.Disposable
import com.spaceace.game.Config
import com.spaceace.game.input.Control
import com.spaceace.game.input.KeyBindings
import com.spaceace.game.input.keyName
import com.spaceace.game.model.Npc
import com.spaceace.game.model.NpcKind
import com.spaceace.game.model.PowerUpType
import com.spaceace.game.model.ShipType
import com.spaceace.game.state.Counter
import com.spaceace.game.state.GameState
import com.spaceace.game.state.Phase
import com.spaceace.game.state.PowerUpInventory
import com.spaceace.game.state.ScreenState
import com.spaceace.game.ui.Bestiary
import com.spaceace.game.ui.HangarCarousel
import com.spaceace.game.ui.Rect
import com.spaceace.game.ui.UiButton
import com.spaceace.game.ui.UiIcon
import com.spaceace.game.ui.UiView
import com.spaceace.game.ui.WrappedText
import com.spaceace.game.ui.bestiaryCellRect
import com.spaceace.game.ui.cardRect
import com.spaceace.game.ui.centeredSlotRect
import com.spaceace.game.ui.description
import com.spaceace.game.ui.fitText
import com.spaceace.game.ui.label
import com.spaceace.game.ui.layout
import com.spaceace.game.ui.saveSummary
import com.spaceace.game.ui.savesCarousel
import com.spaceace.game.ui.scaleSteps
import com.spaceace.game.ui.settingsRowY
import com.spaceace.game.ui.shortLabel
import com.spaceace.game.ui.hudSlotRect
import com.spaceace.game.ui.slotKeyHint
import com.spaceace.game.ui.slotTitle
import com.spaceace.game.ui.storeCarousel
import com.spaceace.game.ui.subtitle
import com.spaceace.game.ui.title
import com.spaceace.game.weapon.Weapon
import com.spaceace.game.weapon.chargeFraction
import ktx.graphics.use
import kotlin.math.ceil
/** HUD и экраны-меню. Только читает GameState; кнопки берутся из той же разметки, что и ввод. */
/** [keyHints] — подписывать клавиши слотов (на desktop; на телефоне клавиатуры нет). */
class UiRenderer(private val sprites: Sprites, private val keyHints: Boolean) : Disposable {
    private val shapes = ShapeRenderer()
    private val batch = SpriteBatch()
    private val fonts = UiFonts()
    private val ui = Config.Ui
    private val w = Config.Display.VIRTUAL_WIDTH
    private val h = Config.Display.VIRTUAL_HEIGHT
    private val ppm = Config.Physics.PIXELS_PER_METER

    private val text = color(Config.Render.TEXT)
    private val dim = color(Config.Render.TEXT_DIM)
    private val panel = color(Config.Render.PANEL)
    private val button = color(Config.Render.BUTTON)
    private val buttonDisabled = color(Config.Render.BUTTON_DISABLED)
    private val outline = color(Config.Render.BUTTON_OUTLINE)
    private val selected = color(Config.Render.SLOT_SELECTED)
    private val slotEmpty = color(Config.Render.SLOT_EMPTY)
    private val fireFill = color(Config.Render.FIRE_FILL)
    private val fireOutline = color(Config.Render.FIRE_OUTLINE)
    private val hpBar = color(Config.Render.HP_BAR)
    private val bossBar = color(Config.Render.BOSS_BAR)
    private val barBack = color(Config.Render.BAR_BACK)
    private val chargeBar = color(Config.Render.CHARGE_BAR)
    private val descriptionBox = color(Config.Render.DESCRIPTION_BOX)
    private val tinted = Color()
    private val glyphs = GlyphLayout()
    // Перенос зависит только от корабля — считается один раз, а не каждый кадр.
    private val descriptions = HashMap<ShipType, WrappedText>()
    private val wrapped = HashMap<WrapKey, WrappedText>()
    private val bossColor = color(Config.Render.BOSS)
    private val enemyColor = color(Config.Render.ENEMY)
    private val allyColor = color(Config.Render.ALLY)

    /** Перенос зависит только от текста и рамки — считается один раз, а не каждый кадр. */
    private data class WrapKey(val paragraphs: List<String>, val width: Float, val height: Float, val maxScale: Float, val minScale: Float)

    fun render(camera: Camera, state: GameState, view: UiView) {
        val buttons = layout(state, view)
        val screen = state.screen
        val hud = screen == ScreenState.PLAYING || screen == ScreenState.PAUSE

        Gdx.gl.glEnable(GL20.GL_BLEND)
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA)
        shapes.use(ShapeRenderer.ShapeType.Filled, camera) {
            if (hud) hudShapes(it, state)
            if (screen != ScreenState.PLAYING) {
                it.color = panel
                it.rect(0f, 0f, w, h)
            }
            when (screen) {
                ScreenState.SHOP -> inventoryShapes(it, state.run.inventory, ui.SHOP_INVENTORY_Y)
                ScreenState.SAVES -> savesShapes(it, state, view)
                ScreenState.BESTIARY -> bestiaryShapes(it, state, view)
                ScreenState.HANGAR -> hangarShapes(it, state, view)
                ScreenState.LOADOUT -> loadoutShapes(it, state)
                else -> Unit
            }
            buttons.forEach { b -> buttonFill(it, b) }
            buttons.forEach { b -> if (b.icon == UiIcon.GEAR) gear(it, b.rect) }
        }
        shapes.use(ShapeRenderer.ShapeType.Line, camera) {
            if (screen == ScreenState.PLAYING) {
                it.color = fireOutline
                it.circle(ui.FIRE_X, ui.FIRE_Y, ui.FIRE_RADIUS, Config.Render.CIRCLE_SEGMENTS * 2)
            }
            it.color = outline
            when (screen) {
                ScreenState.HANGAR -> HangarCarousel.window(view.hangarFirst).indices.map(::cardRect)
                    .forEach { r -> it.rect(r.x, r.y, r.width, r.height) }
                ScreenState.SAVES -> savesCarousel(state.slots.size).range(view.savesFirst).mapIndexed { place, _ -> cardRect(place) }
                    .forEach { r -> it.rect(r.x, r.y, r.width, r.height) }
                ScreenState.LOADOUT -> {
                    it.rect((w - ui.DESCRIPTION_WIDTH) / 2, ui.DESCRIPTION_Y, ui.DESCRIPTION_WIDTH, ui.DESCRIPTION_HEIGHT)
                    repeat(Config.Inventory.SLOTS) { i -> centeredSlotRect(i, ui.LOADOUT_INVENTORY_Y).let { r -> it.rect(r.x, r.y, r.width, r.height) } }
                }
                else -> Unit
            }
            for (b in buttons) {
                it.color = if (b.selected) selected else outline
                it.rect(b.rect.x, b.rect.y, b.rect.width, b.rect.height)
                if (b.selected) it.rect(b.rect.x + 2f, b.rect.y + 2f, b.rect.width - 4f, b.rect.height - 4f)
            }
        }
        batch.use(camera) {
            previews(it, state, view)
            if (hud) hudText(it, state)
            buttons.forEach { b -> label(it, b) }
            screenText(it, state, view)
        }
        Gdx.gl.glDisable(GL20.GL_BLEND)
    }

    /** Размер окна изменился: шрифты перегенерируются под новые пиксели. */
    fun resize(screenScale: Float) {
        fonts.resize(screenScale)
        descriptions.clear()
        wrapped.clear()
    }

    override fun dispose() {
        shapes.dispose()
        batch.dispose()
        fonts.dispose()
    }

    private fun hudShapes(shapes: ShapeRenderer, state: GameState) {
        val player = state.player
        bar(shapes, ui.MARGIN, ui.BAR_Y, ui.HP_BAR_WIDTH, player?.health?.fraction ?: 0f, hpBar)
        boss(state)?.let { bar(shapes, (w - ui.BOSS_BAR_WIDTH) / 2, ui.BOSS_BAR_Y, ui.BOSS_BAR_WIDTH, it.health.fraction, bossBar) }

        if (state.screen == ScreenState.PLAYING) {
            shapes.color = fireFill
            shapes.circle(ui.FIRE_X, ui.FIRE_Y, ui.FIRE_RADIUS, Config.Render.CIRCLE_SEGMENTS * 2)
        }

        val charge = state.run.trigger.chargeFraction(Weapon.of(state.run.weapon).mode)
        if (player != null && charge > 0f) {
            val x = player.position.x * ppm - ui.CHARGE_BAR_WIDTH / 2
            val y = (player.position.y + player.hitbox.top) * ppm + ui.CHARGE_BAR_GAP
            shapes.color = barBack
            shapes.rect(x, y, ui.CHARGE_BAR_WIDTH, ui.CHARGE_BAR_HEIGHT)
            shapes.color = chargeBar
            shapes.rect(x, y, ui.CHARGE_BAR_WIDTH * charge, ui.CHARGE_BAR_HEIGHT)
        }
    }

    /** Неинтерактивный ряд слотов (магазин, снаряжение). */
    private fun inventoryShapes(shapes: ShapeRenderer, inventory: PowerUpInventory, y: Float) {
        inventory.slots.forEachIndexed { i, slot ->
            val rect = centeredSlotRect(i, y)
            shapes.color = slot?.let { typeColor(it.type) } ?: slotEmpty
            shapes.rect(rect.x, rect.y, rect.width, rect.height)
        }
    }

    private fun inventoryText(batch: SpriteBatch, inventory: PowerUpInventory, y: Float) {
        draw(batch, "INVENTORY", w / 2, y + ui.SLOT_SIZE + ui.ITEM_LINE_HEIGHT, ui.SMALL_SCALE, dim)
        inventory.slots.forEachIndexed { i, slot ->
            if (slot == null) return@forEachIndexed
            val rect = centeredSlotRect(i, y)
            draw(batch, slot.label(), rect.centerX, rect.centerY + capHeight(ui.SMALL_SCALE) / 2, ui.SMALL_SCALE)
        }
    }

    private fun savesShapes(shapes: ShapeRenderer, state: GameState, view: UiView) {
        val carousel = savesCarousel(state.slots.size)
        val shown = carousel.range(view.savesFirst)
        if (carousel.scrollable) pageDots(shapes, carousel.total, shown.toSet())
        shown.forEachIndexed { place, _ ->
            val card = cardRect(place)
            shapes.color = button
            shapes.rect(card.x, card.y, card.width, card.height)
        }
    }

    private fun hangarShapes(shapes: ShapeRenderer, state: GameState, view: UiView) {
        val window = HangarCarousel.window(view.hangarFirst)
        if (HangarCarousel.scrollable) pageDots(shapes, ShipType.ALL.size, window.map { it.index }.toSet())
        window.forEachIndexed { slot, (_, ship) ->
            val card = cardRect(slot)
            shapes.color = button
            shapes.rect(card.x, card.y, card.width, card.height)
        }
    }

    private fun loadoutShapes(shapes: ShapeRenderer, state: GameState) {
        shapes.color = descriptionBox
        shapes.rect((w - ui.DESCRIPTION_WIDTH) / 2, ui.DESCRIPTION_Y, ui.DESCRIPTION_WIDTH, ui.DESCRIPTION_HEIGHT)
        inventoryShapes(shapes, loadoutPreview(state), ui.LOADOUT_INVENTORY_Y)
    }

    /** Картинки кораблей и противников на экранах-меню — после подложек карточек, до текста. */
    private fun previews(batch: SpriteBatch, state: GameState, view: UiView) {
        when (state.screen) {
            ScreenState.HANGAR -> HangarCarousel.window(view.hangarFirst).forEachIndexed { slot, (_, ship) ->
                sprites.drawShip(batch, ship, cardRect(slot).centerX, ui.CARD_PREVIEW_Y, ui.CARD_PREVIEW_SCALE, state.meta.owns(ship))
            }
            ScreenState.LOADOUT -> sprites.drawShip(batch, state.meta.selectedShip, w / 2, ui.LOADOUT_PREVIEW_Y, ui.PREVIEW_SCALE)
            ScreenState.BESTIARY -> {
                val entry = view.bestiaryEntry?.let(Bestiary::find)
                if (entry != null) {
                    sprites.drawKind(batch, entry, w / 2, ui.ENTRY_PREVIEW_Y, ui.ENTRY_PREVIEW_SCALE, ui.ENTRY_PREVIEW_MAX)
                    return
                }
                for ((place, kind) in Bestiary.window(view.bestiaryFirst)) {
                    if (kind.id !in state.meta.seen) continue
                    val cell = bestiaryCellRect(place)
                    sprites.drawKind(batch, kind, cell.centerX, cell.y + cell.height * ui.BESTIARY_PREVIEW_AT, ui.BESTIARY_PREVIEW_SCALE, ui.BESTIARY_PREVIEW_MAX)
                }
            }
            else -> Unit
        }
    }

    /** Точки под заголовком слайдера: сколько всего карточек и какие сейчас на экране. */
    private fun pageDots(shapes: ShapeRenderer, count: Int, shown: Set<Int>) {
        val startX = w / 2 - (count - 1) * ui.PAGE_DOT_GAP / 2
        for (i in 0 until count) {
            shapes.color = if (i in shown) text else dim
            shapes.circle(startX + i * ui.PAGE_DOT_GAP, ui.PAGE_DOTS_Y, ui.PAGE_DOT_RADIUS, Config.Render.CIRCLE_SEGMENTS)
        }
    }

    /** Каким будет инвентарь на старте забега с текущим выбором. */
    private fun loadoutPreview(state: GameState): PowerUpInventory {
        val ship = state.meta.selectedShip
        return PowerUpInventory.loadout(ship.stats.baseWeapon, state.meta.storeFor(ship).type)
    }

    private fun bar(shapes: ShapeRenderer, x: Float, y: Float, width: Float, fraction: Float, fill: Color) {
        shapes.color = barBack
        shapes.rect(x, y, width, ui.BAR_HEIGHT)
        shapes.color = fill
        shapes.rect(x, y, width * fraction.coerceIn(0f, 1f), ui.BAR_HEIGHT)
    }

    private fun buttonFill(shapes: ShapeRenderer, b: UiButton) {
        shapes.color = when {
            b.type != null -> typeColor(b.type).let { if (b.enabled) it else tinted.set(it).mul(1f, 1f, 1f, 0.4f) }
            b.label.isEmpty() && b.icon == null -> slotEmpty
            b.enabled -> button
            else -> buttonDisabled
        }
        shapes.rect(b.rect.x, b.rect.y, b.rect.width, b.rect.height)
    }

    /** Шестерёнка: круг с зубцами по кругу и отверстие цвета кнопки. */
    private fun gear(shapes: ShapeRenderer, rect: Rect) {
        val size = minOf(rect.width, rect.height)
        val radius = size * ui.GEAR_RADIUS
        val tooth = size * ui.GEAR_TOOTH
        shapes.color = text
        for (i in 0 until ui.GEAR_TEETH) {
            val degrees = 360f * i / ui.GEAR_TEETH
            // Зубец — квадрат, повёрнутый вокруг центра кнопки и вынесенный к краю круга.
            shapes.rect(rect.centerX - tooth / 2, rect.centerY + radius - tooth / 2, tooth / 2, tooth / 2 - radius, tooth, tooth, 1f, 1f, degrees)
        }
        shapes.circle(rect.centerX, rect.centerY, radius, Config.Render.CIRCLE_SEGMENTS)
        shapes.color = button
        shapes.circle(rect.centerX, rect.centerY, size * ui.GEAR_HOLE, Config.Render.CIRCLE_SEGMENTS)
    }

    private fun hudText(batch: SpriteBatch, state: GameState) {
        val player = state.player
        draw(batch, "HP ${player?.health?.current?.let { ceil(it).toInt() } ?: 0}", ui.MARGIN + ui.HP_BAR_WIDTH / 2, ui.BAR_Y + ui.BAR_HEIGHT - 4f, ui.SMALL_SCALE)
        draw(batch, "\$${state.meta.wallet.balance}", w - ui.MARGIN, ui.TOP_TEXT_Y, ui.TEXT_SCALE, align = Align.right)
        val phase = state.run.phase
        val status = when (phase) {
            is Phase.Level -> {
                val left = ceil(Config.Progression.LEVEL_DURATION - state.run.phaseTime).toInt().coerceAtLeast(0)
                "LEVEL ${phase.level}   ${left / 60}:${(left % 60).toString().padStart(2, '0')}"
            }
            is Phase.BossFight -> "LEVEL ${phase.level}   BOSS"
            is Phase.Shop -> "LEVEL ${phase.level}   SHOP"
        }
        draw(batch, status, w / 2, ui.TOP_TEXT_Y, ui.TEXT_SCALE)

        val effects = state.run.effects.remaining.entries.joinToString("   ") { (type, left) -> "${type.shortLabel()} ${ceil(left).toInt()}" }
        if (effects.isNotEmpty()) draw(batch, effects, ui.MARGIN, ui.EFFECTS_Y, ui.SMALL_SCALE, align = Align.left)
        if (keyHints) {
            val slotRect = if (state.screen == ScreenState.PAUSE) { i: Int -> centeredSlotRect(i, ui.PAUSE_SLOT_Y) } else ::hudSlotRect
            for (i in 0 until Config.Inventory.SLOTS) {
                val r = slotRect(i)
                val hint = slotKeyHint(state.settings.bindings, i)
                draw(batch, hint, r.x + ui.KEY_HINT_INSET, r.y + r.height - ui.KEY_HINT_INSET, ui.KEY_HINT_SCALE, dim, align = Align.left)
            }
        }
        if (state.screen == ScreenState.PLAYING) {
            draw(batch, "FIRE", ui.FIRE_X, ui.FIRE_Y + capHeight(ui.TEXT_SCALE) / 2, ui.TEXT_SCALE, dim)
            draw(batch, state.run.weapon.label(), ui.FIRE_X, ui.WEAPON_LABEL_Y, ui.SMALL_SCALE, dim)
        }
    }

    private fun screenText(batch: SpriteBatch, state: GameState, view: UiView) {
        val money = "\$${state.meta.wallet.balance}"
        when (state.screen) {
            ScreenState.PLAYING -> Unit
            ScreenState.SAVES -> {
                draw(batch, "SELECT SAVE", w / 2, ui.SCREEN_TITLE_Y, ui.TITLE_SCALE)
                savesCarousel(state.slots.size).range(view.savesFirst).forEachIndexed { place, slot ->
                    val x = cardRect(place).centerX
                    val saved = state.slots[slot]
                    draw(batch, slotTitle(slot), x, ui.SAVE_SLOT_TITLE_Y, ui.TEXT_SCALE, if (saved == null) dim else text)
                    val lines = saved?.saveSummary() ?: listOf("EMPTY")
                    lines.forEachIndexed { i, line ->
                        draw(batch, line, x, ui.SAVE_SLOT_INFO_Y - i * ui.ITEM_LINE_HEIGHT, ui.SMALL_SCALE, if (i == 0 && saved != null) text else dim)
                    }
                }
            }
            ScreenState.HANGAR -> {
                draw(batch, "HANGAR", w / 2, ui.SCREEN_TITLE_Y, ui.TITLE_SCALE)
                draw(batch, money, w / 2, ui.SCREEN_MONEY_Y, ui.TEXT_SCALE)
                HangarCarousel.window(view.hangarFirst).forEachIndexed { slot, (_, ship) ->
                    val x = cardRect(slot).centerX
                    val color = if (state.meta.owns(ship)) text else dim
                    draw(batch, ship.title(), x, ui.CARD_NAME_Y, ui.TEXT_SCALE, color)
                    draw(batch, ship.subtitle(), x, ui.CARD_NAME_Y - ui.ITEM_LINE_HEIGHT, ui.SMALL_SCALE, color)
                    draw(batch, "HP ${ship.stats.maxHealth.toInt()}", x, ui.CARD_NAME_Y - ui.ITEM_LINE_HEIGHT * 2, ui.SMALL_SCALE, dim)
                    draw(batch, ship.stats.baseWeapon.label(), x, ui.CARD_NAME_Y - ui.ITEM_LINE_HEIGHT * 3, ui.SMALL_SCALE, dim)
                }
            }
            ScreenState.LOADOUT -> {
                val ship = state.meta.selectedShip
                draw(batch, ship.title(), w / 2, ui.SCREEN_TITLE_Y, ui.TITLE_SCALE)
                draw(batch, ship.subtitle(), w / 2, ui.SCREEN_MONEY_Y, ui.TEXT_SCALE)
                draw(batch, money, w - ui.MARGIN, ui.TOP_TEXT_Y, ui.TEXT_SCALE, align = Align.right)
                val stats = "HP ${ship.stats.maxHealth.toInt()}    BASE: ${ship.stats.baseWeapon.label()}"
                draw(batch, stats, w / 2, ui.LOADOUT_STATS_Y, ui.SMALL_SCALE)
                descriptionText(batch, ship)
                val stores = storeCarousel(ship)
                val shown = stores.range(view.storeFirst)
                val label = if (stores.scrollable) "STORES  ${shown.first + 1}-${shown.last + 1} / ${stores.total}" else "STORES"
                draw(batch, label, w / 2, ui.LOADOUT_STORES_LABEL_Y, ui.SMALL_SCALE, dim)
                inventoryText(batch, loadoutPreview(state), ui.LOADOUT_INVENTORY_Y)
            }
            ScreenState.BESTIARY -> bestiaryText(batch, state, view)
            ScreenState.META_SHOP -> {
                draw(batch, "SHOP", w / 2, ui.SCREEN_TITLE_Y, ui.TITLE_SCALE)
                draw(batch, money, w / 2, ui.SCREEN_MONEY_Y, ui.TEXT_SCALE)
                for (counter in Counter.entries) {
                    draw(batch, counter.name, cardRect(counter.ordinal).centerX, ui.GOODS_HEADER_Y, ui.TEXT_SCALE)
                }
                draw(batch, "bought goods start dropping in runs", w / 2, ui.META_SHOP_NOTE_Y, ui.SMALL_SCALE, dim)
                draw(batch, "(stubs for now: no effect yet)", w / 2, ui.META_SHOP_NOTE_Y - ui.ITEM_LINE_HEIGHT, ui.SMALL_SCALE, dim)
            }
            ScreenState.MENU -> {
                title(batch, "SPACE ACE")
                val keys = state.settings.bindings
                val move = listOf(Control.MOVE_LEFT, Control.MOVE_RIGHT, Control.MOVE_UP, Control.MOVE_DOWN).joinToString("/") { keyOf(keys, it) }
                draw(batch, "drag or $move - move   ${keyOf(keys, Control.FIRE)} - shoot", w / 2, ui.HINT_Y, ui.SMALL_SCALE, dim)
                val slots = Control.SLOTS.joinToString("/") { keyOf(keys, it) }
                draw(batch, "$slots - slots   ESC - pause", w / 2, ui.HINT_Y - ui.ITEM_LINE_HEIGHT, ui.SMALL_SCALE, dim)
            }
            ScreenState.SETTINGS -> {
                draw(batch, "SETTINGS", w / 2, ui.SCREEN_TITLE_Y, ui.TITLE_SCALE)
                Control.entries.forEachIndexed { row, control ->
                    val y = settingsRowY(row) + ui.SETTINGS_ROW_HEIGHT / 2 + capHeight(ui.TEXT_SCALE) / 2
                    val color = if (state.settings.bindings.keyOf(control) == null) dim else text
                    draw(batch, control.label(), ui.SETTINGS_ROW_INSET, y, ui.TEXT_SCALE, color, align = Align.left)
                }
                val hint = if (view.awaitingKey != null) "press a key   ESC - cancel" else "click a key to change it   ESC - back"
                draw(batch, hint, w / 2, ui.SETTINGS_HINT_Y, ui.SMALL_SCALE, dim)
            }
            ScreenState.PAUSE -> {
                title(batch, "PAUSED")
                draw(batch, "STORES", w / 2, ui.PAUSE_SLOT_Y + ui.SLOT_SIZE + ui.STORES_LABEL_GAP, ui.SMALL_SCALE, dim)
                if (view.showStorage) {
                    val items = state.run.storage.items
                    val lines = if (items.isEmpty()) listOf("no items yet") else items.take(ui.MAX_ITEM_LINES).map { it.label() }
                    lines.forEachIndexed { i, line ->
                        draw(batch, line, w / 2, ui.PAUSE_ITEMS_Y - ui.ITEM_LINE_HEIGHT * (i + 1), ui.SMALL_SCALE, dim)
                    }
                }
            }
            ScreenState.SHOP -> {
                title(batch, "SHOP")
                draw(batch, money, w / 2, ui.SUBTITLE_Y, ui.TEXT_SCALE)
                inventoryText(batch, state.run.inventory, ui.SHOP_INVENTORY_Y)
            }
            ScreenState.GAME_OVER -> {
                title(batch, "GAME OVER")
                draw(batch, "reached level ${state.run.phase.level}", w / 2, ui.SUBTITLE_Y, ui.TEXT_SCALE)
                draw(batch, "money kept: $money", w / 2, ui.INFO_Y, ui.TEXT_SCALE, dim)
            }
            ScreenState.VICTORY -> {
                title(batch, "VICTORY!")
                draw(batch, "both bosses destroyed", w / 2, ui.SUBTITLE_Y, ui.TEXT_SCALE)
                draw(batch, money, w / 2, ui.INFO_Y, ui.TEXT_SCALE, dim)
            }
        }
    }

    private fun keyOf(bindings: KeyBindings, control: Control) = bindings.keyOf(control)?.let(::keyName) ?: "-"

    private fun label(batch: SpriteBatch, b: UiButton) {
        if (b.label.isEmpty()) return
        val preferred = if (b.rect.width <= ui.SLOT_SIZE) ui.SMALL_SCALE else ui.BUTTON_TEXT_SCALE
        // Длинная подпись ужимается под ширину кнопки, а не вылезает за края.
        glyphs.setText(fonts.at(preferred), b.label)
        val available = b.rect.width - 2 * ui.BUTTON_PADDING
        val scale = if (glyphs.width > available) preferred * available / glyphs.width else preferred
        draw(batch, b.label, b.rect.centerX, b.rect.centerY + capHeight(scale) / 2, scale, if (b.enabled) text else dim)
    }

    /** Описание корабля, перенесённое по словам и отцентрованное по вертикали внутри рамки. */
    private fun descriptionText(batch: SpriteBatch, ship: ShipType) {
        val wrapped = descriptions.getOrPut(ship) { wrapDescription(ship) }
        val lineHeight = fonts.at(1f).lineHeight * wrapped.scale
        val blockTop = ui.DESCRIPTION_Y + ui.DESCRIPTION_HEIGHT / 2 + wrapped.lines.size * lineHeight / 2
        wrapped.lines.forEachIndexed { i, line ->
            draw(batch, line, w / 2, blockTop - i * lineHeight, wrapped.scale, dim)
        }
    }

    private fun bestiaryShapes(shapes: ShapeRenderer, state: GameState, view: UiView) {
        val entry = view.bestiaryEntry?.let(Bestiary::find)
        if (entry != null) {
            if (spriteOf(entry) == null) {
                shapes.color = roleColor(entry)
                drawHitbox(shapes, entry.body.hitbox, w / 2, ui.ENTRY_PREVIEW_Y, ui.ENTRY_PREVIEW_SCALE, entry.body.startAngle)
            }
            shapes.color = descriptionBox
            shapes.rect((w - ui.DESCRIPTION_WIDTH) / 2, ui.ENTRY_TEXT_Y, ui.DESCRIPTION_WIDTH, ui.ENTRY_TEXT_HEIGHT)
            return
        }
        for ((place, kind) in Bestiary.window(view.bestiaryFirst)) {
            if (kind.id !in state.meta.seen || spriteOf(kind) != null) continue
            val cell = bestiaryCellRect(place)
            shapes.color = roleColor(kind)
            val cy = cell.y + cell.height * ui.BESTIARY_PREVIEW_AT
            drawHitbox(shapes, kind.body.hitbox, cell.centerX, cy, ui.BESTIARY_PREVIEW_SCALE, kind.body.startAngle)
        }
    }

    private fun bestiaryText(batch: SpriteBatch, state: GameState, view: UiView) {
        val entry = view.bestiaryEntry?.let(Bestiary::find)
        if (entry != null) {
            val title = fitted(listOf(entry.title), w - 4 * ui.MARGIN, ui.ENTRY_TITLE_HEIGHT, ui.TEXT_SCALE, ui.SMALL_SCALE)
            drawBlock(batch, title, w / 2, ui.ENTRY_TITLE_Y, text)
            draw(batch, "${Bestiary.roleLabel(entry)}    HP ${entry.body.maxHealth.toInt()}", w / 2, ui.ENTRY_STATS_Y, ui.TEXT_SCALE, dim)
            val body = fitted(
                entry.description,
                ui.DESCRIPTION_WIDTH - 2 * ui.DESCRIPTION_PADDING,
                ui.ENTRY_TEXT_HEIGHT - 2 * ui.DESCRIPTION_PADDING,
                ui.TEXT_SCALE,
                ui.DESCRIPTION_MIN_SCALE,
            )
            drawBlock(batch, body, w / 2, ui.ENTRY_TEXT_Y + ui.ENTRY_TEXT_HEIGHT / 2, dim)
            return
        }
        draw(batch, "BESTIARY", w / 2, ui.SCREEN_TITLE_Y, ui.TITLE_SCALE)
        val opened = Bestiary.entries.count { it.id in state.meta.seen }
        draw(batch, "OPENED $opened / ${Bestiary.entries.size}", w / 2, ui.SCREEN_MONEY_Y, ui.TEXT_SCALE, dim)
        for ((place, kind) in Bestiary.window(view.bestiaryFirst)) {
            val cell = bestiaryCellRect(place)
            if (kind.id !in state.meta.seen) {
                draw(batch, "???", cell.centerX, cell.centerY + capHeight(ui.TEXT_SCALE) / 2, ui.TEXT_SCALE, dim)
                continue
            }
            val name = fitted(listOf(kind.title), cell.width - 2 * ui.BUTTON_PADDING, ui.BESTIARY_NAME_HEIGHT, ui.SMALL_SCALE, ui.DESCRIPTION_MIN_SCALE)
            drawBlock(batch, name, cell.centerX, cell.y + ui.BESTIARY_NAME_HEIGHT / 2 + ui.ITEM_LINE_HEIGHT, text)
            draw(batch, Bestiary.roleLabel(kind), cell.centerX, cell.y + ui.ITEM_LINE_HEIGHT * ui.BESTIARY_ROLE_LINE, ui.SMALL_SCALE, dim)
        }
    }

    private fun roleColor(kind: NpcKind): Color = when (Bestiary.roleLabel(kind)) {
        "ALLY" -> allyColor
        "BOSS" -> bossColor
        else -> enemyColor
    }

    /** Текст, перенесённый по словам и ужатый от [maxScale] до [minScale], чтобы влезть в [width] × [height]. */
    private fun fitted(paragraphs: List<String>, width: Float, height: Float, maxScale: Float, minScale: Float): WrappedText =
        wrapped.getOrPut(WrapKey(paragraphs, width, height, maxScale, minScale)) {
            val font = fonts.at(1f)
            fitText(
                paragraphs = paragraphs,
                width = width,
                height = height,
                scales = scaleSteps(maxScale, minScale, ui.DESCRIPTION_SCALE_STEP),
                lineHeight = font.lineHeight,
                measure = { line ->
                    glyphs.setText(font, line)
                    glyphs.width
                },
            )
        }

    /** Строки блока по центру [cx], отцентрованные по вертикали вокруг [centerY]. */
    private fun drawBlock(batch: SpriteBatch, block: WrappedText, cx: Float, centerY: Float, color: Color) {
        val lineHeight = fonts.at(1f).lineHeight * block.scale
        val top = centerY + block.lines.size * lineHeight / 2
        block.lines.forEachIndexed { i, line -> draw(batch, line, cx, top - i * lineHeight, block.scale, color) }
    }

    private fun wrapDescription(ship: ShipType): WrappedText {
        val font = fonts.at(1f)
        return fitText(
            paragraphs = ship.description(),
            width = ui.DESCRIPTION_WIDTH - 2 * ui.DESCRIPTION_PADDING,
            height = ui.DESCRIPTION_HEIGHT - 2 * ui.DESCRIPTION_PADDING,
            scales = scaleSteps(ui.SMALL_SCALE, ui.DESCRIPTION_MIN_SCALE, ui.DESCRIPTION_SCALE_STEP),
            lineHeight = font.lineHeight,
            measure = { text ->
                glyphs.setText(font, text)
                glyphs.width
            },
        )
    }

    private fun capHeight(scale: Float): Float {
        return fonts.at(scale).capHeight
    }

    private fun title(batch: SpriteBatch, value: String) = draw(batch, value, w / 2, ui.TITLE_Y, ui.TITLE_SCALE)

    private fun draw(batch: SpriteBatch, value: String, x: Float, y: Float, scale: Float, color: Color = text, align: Int = Align.center) {
        val font = fonts.at(scale)
        font.color = color
        font.draw(batch, value, x, y, 0f, align, false)
    }

    private fun boss(state: GameState): Npc? = state.entities.ofType<Npc>().firstOrNull { it.isBoss }

    private fun typeColor(type: PowerUpType): Color = tinted.set(powerUpColor(type)).mul(1f, 1f, 1f, Config.Render.SLOT_FILL_ALPHA)

    private fun color(rgba8888: Long) = Color(rgba8888.toInt())
}

/**
 * Цвет усилителя по типу — общий для поля и слотов. Исчерпывающий when по готовым цветам: новый тип усилителя
 * без цвета не скомпилируется (раньше цвет искался в словаре, и забытый в нём цвет ронял игру при отрисовке).
 */
fun powerUpColor(type: PowerUpType): Color = when (type) {
    PowerUpType.Shield -> PowerUpColors.shield
    PowerUpType.Repair -> PowerUpColors.repair
    PowerUpType.HeavyRounds -> PowerUpColors.heavyRounds
    PowerUpType.DroneCall -> PowerUpColors.droneCall
    is PowerUpType.WeaponPickup -> PowerUpColors.weaponPickup
    is PowerUpType.PerkPickup -> PowerUpColors.perkPickup
    is PowerUpType.StoreStub -> PowerUpColors.stub
    is PowerUpType.ItemPickup -> PowerUpColors.itemPickup
}

/** Цвета создаются один раз; снаружи их не меняют (рендер берёт копию через tinted, когда нужна прозрачность). */
private object PowerUpColors {
    val shield = Color(Config.Render.SHIELD.toInt())
    val repair = Color(Config.Render.REPAIR.toInt())
    val heavyRounds = Color(Config.Render.HEAVY_ROUNDS.toInt())
    val droneCall = Color(Config.Render.DRONE_CALL.toInt())
    val weaponPickup = Color(Config.Render.WEAPON_PICKUP.toInt())
    val perkPickup = Color(Config.Render.PERK_PICKUP.toInt())
    val stub = Color(Config.Render.STUB.toInt())
    val itemPickup = Color(Config.Render.ITEM_PICKUP.toInt())
}
