package com.spaceace.game.ui

import com.spaceace.game.Config
import com.spaceace.game.input.Control
import com.spaceace.game.input.KeyBindings
import com.spaceace.game.input.keyName
import com.spaceace.game.state.Counter
import com.spaceace.game.state.GameState
import com.spaceace.game.state.MetaState
import com.spaceace.game.state.ScreenState
import com.spaceace.game.state.Slot
private val W = Config.Display.VIRTUAL_WIDTH
private val H = Config.Display.VIRTUAL_HEIGHT
private val ui = Config.Ui

fun centeredRect(y: Float, width: Float = ui.BUTTON_WIDTH, height: Float = ui.BUTTON_HEIGHT) =
    Rect((W - width) / 2, y, width, height)

fun hudSlotRect(index: Int) = Rect(ui.MARGIN + index * (ui.SLOT_SIZE + ui.SLOT_GAP), ui.HUD_SLOT_Y, ui.SLOT_SIZE, ui.SLOT_SIZE)

/** Ряд слотов по центру экрана (пауза, магазин). */
fun centeredSlotRect(index: Int, y: Float): Rect {
    val rowWidth = Config.Inventory.SLOTS * ui.SLOT_SIZE + (Config.Inventory.SLOTS - 1) * ui.SLOT_GAP
    val startX = (W - rowWidth) / 2
    return Rect(startX + index * (ui.SLOT_SIZE + ui.SLOT_GAP), y, ui.SLOT_SIZE, ui.SLOT_SIZE)
}

fun clearSlotRect(index: Int): Rect {
    val slot = centeredSlotRect(index, ui.PAUSE_SLOT_Y)
    return Rect(slot.x, slot.y - ui.SLOT_GAP - ui.CLEAR_HEIGHT, slot.width, ui.CLEAR_HEIGHT)
}

fun pauseButtonRect() =
    Rect(W - ui.MARGIN - ui.PAUSE_BUTTON_SIZE, ui.BOSS_BAR_Y - ui.PAUSE_BUTTON_SIZE, ui.PAUSE_BUTTON_SIZE, ui.PAUSE_BUTTON_SIZE)

/** Кнопка настроек — там же, где кнопка паузы в бою (правый верхний угол). */
fun settingsButtonRect() = pauseButtonRect()

private fun settingsButton() = UiButton(UiAction.OpenSettings, settingsButtonRect(), "", icon = UiIcon.GEAR)

/** Вкладки настроек — ряд по центру под заголовком. */
fun settingsTabRect(index: Int): Rect {
    val count = SettingsTab.entries.size
    val total = count * ui.SETTINGS_TAB_WIDTH + (count - 1) * ui.SLOT_GAP
    return Rect((W - total) / 2 + index * (ui.SETTINGS_TAB_WIDTH + ui.SLOT_GAP), ui.SETTINGS_TAB_Y, ui.SETTINGS_TAB_WIDTH, ui.SETTINGS_TAB_HEIGHT)
}

/** Строка [row] списка клавиш: название действия рисуется слева, кнопка с клавишей — справа. */
fun settingsRowY(row: Int) = ui.SETTINGS_FIRST_ROW_Y - row * (ui.SETTINGS_ROW_HEIGHT + ui.SETTINGS_ROW_GAP)

fun settingsKeyRect(row: Int) =
    Rect(W - ui.SETTINGS_ROW_INSET - ui.SETTINGS_KEY_WIDTH, settingsRowY(row), ui.SETTINGS_KEY_WIDTH, ui.SETTINGS_ROW_HEIGHT)

/** Подпись кнопки клавиши: ждём нажатия, клавиша или прочерк, если действие без клавиши. */
fun keyLabel(control: Control, bindings: KeyBindings, awaiting: Control?): String =
    if (control == awaiting) "PRESS KEY" else bindings.keyOf(control)?.let(::keyName) ?: "-"

/** Место [slot] карточки на экране ангара (0 — левая) и кнопка под ней; какой корабль там — решает слайдер. */
fun cardRect(slot: Int): Rect {
    val shown = ui.HANGAR_VISIBLE_CARDS
    val total = shown * ui.CARD_WIDTH + (shown - 1) * ui.CARD_GAP
    return Rect((W - total) / 2 + slot * (ui.CARD_WIDTH + ui.CARD_GAP), ui.CARD_Y, ui.CARD_WIDTH, ui.CARD_HEIGHT)
}

fun cardButtonRect(slot: Int): Rect {
    val card = cardRect(slot)
    return Rect(card.x, ui.CARD_BUTTON_Y, card.width, ui.CARD_BUTTON_HEIGHT)
}

/** Стрелки слайдера бестиария — по краям нижнего ряда с кнопкой BACK. */
fun bestiaryArrowRect(left: Boolean): Rect {
    val x = if (left) ui.MARGIN * 2 else W - ui.MARGIN * 2 - ui.BESTIARY_ARROW_WIDTH
    return Rect(x, ui.BOTTOM_BUTTON_Y, ui.BESTIARY_ARROW_WIDTH, ui.BUTTON_HEIGHT)
}

/** Кнопка удаления — внутри карточки слота, у нижнего края. */
fun saveDeleteRect(place: Int): Rect {
    val card = cardRect(place)
    return Rect(card.x + ui.SLOT_GAP, ui.SAVE_DELETE_Y, card.width - 2 * ui.SLOT_GAP, ui.SAVE_DELETE_HEIGHT)
}

/** Стрелки слайдера — по краям ряда с кнопкой SHOP, выровнены по краям карточек (ангар и слоты сохранения). */
fun hangarLeftArrowRect() = Rect(cardRect(0).x, ui.HANGAR_SHOP_Y, ui.ARROW_WIDTH, ui.BUTTON_HEIGHT)

fun hangarRightArrowRect(): Rect {
    val last = cardRect(ui.HANGAR_VISIBLE_CARDS - 1)
    return Rect(last.x + last.width - ui.ARROW_WIDTH, ui.HANGAR_SHOP_Y, ui.ARROW_WIDTH, ui.BUTTON_HEIGHT)
}

/** Место [slot] в списке подвесов на экране (0 — верхнее); какой вариант там — решает слайдер. */
fun storeOptionRect(slot: Int) =
    centeredRect(ui.LOADOUT_FIRST_STORE_Y - slot * (ui.STORE_BUTTON_HEIGHT + ui.SLOT_GAP), ui.STORE_BUTTON_WIDTH, ui.STORE_BUTTON_HEIGHT)

/** Стрелки слайдера подвесов — справа от верхнего и нижнего видимого варианта. */
fun storeUpArrowRect(): Rect {
    val top = storeOptionRect(0)
    return Rect(top.x + top.width + ui.SLOT_GAP, top.y, ui.STORE_ARROW_WIDTH, top.height)
}

fun storeDownArrowRect(): Rect {
    val bottom = storeOptionRect(ui.LOADOUT_VISIBLE_STORES - 1)
    return Rect(bottom.x + bottom.width + ui.SLOT_GAP, bottom.y, ui.STORE_ARROW_WIDTH, bottom.height)
}

/** Две кнопки внизу экрана: слева «назад», справа действие. */
fun bottomLeftRect() = Rect(W / 2 - ui.CARD_GAP / 2 - ui.HALF_BUTTON_WIDTH, ui.BOTTOM_BUTTON_Y, ui.HALF_BUTTON_WIDTH, ui.BUTTON_HEIGHT)

fun bottomRightRect() = Rect(W / 2 + ui.CARD_GAP / 2, ui.BOTTOM_BUTTON_Y, ui.HALF_BUTTON_WIDTH, ui.BUTTON_HEIGHT)

/** Товар на прилавке магазина меню: ITEMS — левая колонка, STORES — правая. */
fun goodRect(counter: Counter, index: Int): Rect {
    val column = cardRect(counter.ordinal)
    return Rect(column.x, ui.FIRST_GOOD_Y - index * (ui.GOOD_BUTTON_HEIGHT + ui.SLOT_GAP), column.width, ui.GOOD_BUTTON_HEIGHT)
}

fun offerRect(index: Int) =
    centeredRect(ui.SHOP_FIRST_OFFER_Y - index * (ui.OFFER_HEIGHT + ui.OFFER_GAP), ui.OFFER_WIDTH, ui.OFFER_HEIGHT)

/** Все кнопки текущего экрана. Рендер рисует их, UI-ввод ищет попадание — разметка одна. */
fun layout(state: GameState, view: UiView): List<UiButton> = when (state.screen) {
    ScreenState.MENU -> listOf(UiButton(UiAction.OpenSaves, centeredRect(ui.PRIMARY_BUTTON_Y), "PLAY"), settingsButton())

    ScreenState.SETTINGS -> buildList {
        SettingsTab.entries.forEachIndexed { i, tab ->
            add(UiButton(UiAction.OpenSettingsTab(tab), settingsTabRect(i), tab.label(), selected = tab == view.settingsTab))
        }
        when (view.settingsTab) {
            SettingsTab.CONTROLS -> Control.entries.forEachIndexed { row, control ->
                val label = keyLabel(control, state.settings.bindings, view.awaitingKey)
                add(UiButton(UiAction.AwaitKey(control), settingsKeyRect(row), label, selected = control == view.awaitingKey))
            }
        }
        add(UiButton(UiAction.CloseSettings, bottomLeftRect(), "BACK"))
        add(UiButton(UiAction.ResetControls, bottomRightRect(), "DEFAULTS", enabled = state.settings.bindings != KeyBindings.DEFAULT))
    }

    ScreenState.SAVES -> buildList {
        val carousel = savesCarousel(state.slots.size)
        carousel.range(view.savesFirst).forEachIndexed { place, slot ->
            val saved = state.slots[slot]
            add(UiButton(UiAction.ChooseSave(slot), cardButtonRect(place), if (saved == null) "NEW GAME" else "LOAD"))
            if (saved != null) {
                val label = if (view.confirmDelete == slot) "CONFIRM?" else "DELETE"
                add(UiButton(UiAction.DeleteSave(slot), saveDeleteRect(place), label, selected = view.confirmDelete == slot))
            }
        }
        if (carousel.scrollable) {
            val first = carousel.clamp(view.savesFirst)
            add(UiButton(UiAction.ScrollSaves(-1), hangarLeftArrowRect(), "<", enabled = first > 0))
            add(UiButton(UiAction.ScrollSaves(1), hangarRightArrowRect(), ">", enabled = first < carousel.lastFirst))
        }
        add(UiButton(UiAction.ToMenu, centeredRect(ui.BOTTOM_BUTTON_Y), "BACK"))
    }

    ScreenState.HANGAR -> buildList {
        val meta = state.meta
        HangarCarousel.window(view.hangarFirst).forEachIndexed { slot, (_, ship) ->
            val button = if (meta.owns(ship)) {
                UiButton(UiAction.SelectShip(ship), cardButtonRect(slot), "SELECT", selected = ship == meta.selectedShip)
            } else {
                val price = ship.stats.price
                UiButton(UiAction.BuyShip(ship), cardButtonRect(slot), "BUY \$$price", enabled = meta.wallet.canAfford(price))
            }
            add(button)
        }
        if (HangarCarousel.scrollable) {
            val first = HangarCarousel.clamp(view.hangarFirst)
            add(UiButton(UiAction.ScrollHangar(-1), hangarLeftArrowRect(), "<", enabled = first > 0))
            add(UiButton(UiAction.ScrollHangar(1), hangarRightArrowRect(), ">", enabled = first < HangarCarousel.lastFirst))
        }
        add(UiButton(UiAction.OpenBestiary, centeredRect(ui.HANGAR_BESTIARY_Y, height = ui.HANGAR_BESTIARY_HEIGHT), "BESTIARY"))
        add(UiButton(UiAction.OpenMetaShop, centeredRect(ui.HANGAR_SHOP_Y), "SHOP"))
        add(UiButton(UiAction.OpenSaves, centeredRect(ui.BOTTOM_BUTTON_Y), "BACK"))
        add(settingsButton())
    }

    ScreenState.LOADOUT -> buildList {
        val meta = state.meta
        val ship = meta.selectedShip
        val options = ship.stats.stores
        // Подсветка по номеру: одинаковые варианты в списке не должны подсвечиваться все сразу.
        val chosen = options.indexOf(meta.storeFor(ship))
        val carousel = storeCarousel(ship)
        carousel.range(view.storeFirst).forEachIndexed { slot, i ->
            val option = options[i]
            val owned = meta.owns(ship, option)
            val label = when {
                option.price == 0 -> "${option.type.longLabel()} (STARTER)"
                owned -> option.type.longLabel()
                else -> "${option.type.longLabel()}  \$${option.price}"
            }
            val enabled = owned || meta.wallet.canAfford(option.price)
            add(UiButton(UiAction.PickStore(i), storeOptionRect(slot), label, enabled, option.type, selected = i == chosen))
        }
        if (carousel.scrollable) {
            val first = carousel.clamp(view.storeFirst)
            add(UiButton(UiAction.ScrollStores(-1), storeUpArrowRect(), "^", enabled = first > 0))
            add(UiButton(UiAction.ScrollStores(1), storeDownArrowRect(), "v", enabled = first < carousel.lastFirst))
        }
        add(UiButton(UiAction.OpenHangar, bottomLeftRect(), "BACK"))
        add(UiButton(UiAction.Play, bottomRightRect(), "START"))
    }

    ScreenState.BESTIARY -> buildList {
        if (view.bestiaryEntry != null) {
            add(UiButton(UiAction.CloseEntry, centeredRect(ui.BOTTOM_BUTTON_Y), "BACK"))
            return@buildList
        }
        // Пустая подпись: карточку рисует UiRenderer (фигура, название, роль); кнопка — только область нажатия.
        for ((place, kind) in Bestiary.window(view.bestiaryFirst)) {
            add(UiButton(UiAction.OpenEntry(kind.id), bestiaryCellRect(place), "", enabled = kind.id in state.meta.seen))
        }
        val carousel = Bestiary.carousel
        if (carousel.scrollable) {
            val first = carousel.clamp(view.bestiaryFirst)
            add(UiButton(UiAction.ScrollBestiary(-1), bestiaryArrowRect(left = true), "^", enabled = first > 0))
            add(UiButton(UiAction.ScrollBestiary(1), bestiaryArrowRect(left = false), "v", enabled = first < carousel.lastFirst))
        }
        add(UiButton(UiAction.OpenHangar, centeredRect(ui.BOTTOM_BUTTON_Y), "BACK"))
    }

    ScreenState.META_SHOP -> buildList {
        val meta = state.meta
        for (counter in Counter.entries) {
            MetaState.catalog(counter).forEachIndexed { i, good ->
                val owned = meta.owns(counter, i)
                val label = if (owned) "${good.name}  OWNED" else "${good.name}  \$${good.price}"
                add(UiButton(UiAction.BuyGood(counter, i), goodRect(counter, i), label, enabled = !owned && meta.wallet.canAfford(good.price)))
            }
        }
        add(UiButton(UiAction.OpenHangar, centeredRect(ui.BOTTOM_BUTTON_Y), "BACK"))
    }

    ScreenState.PLAYING ->
        listOf(UiButton(UiAction.Pause, pauseButtonRect(), "II")) + slotButtons(state, ::hudSlotRect)

    ScreenState.PAUSE -> buildList {
        add(UiButton(UiAction.Resume, centeredRect(ui.PAUSE_RESUME_Y), "RESUME"))
        addAll(slotButtons(state) { centeredSlotRect(it, ui.PAUSE_SLOT_Y) })
        val locked = state.run.inventory.lockedSlots
        state.run.inventory.slots.forEachIndexed { i, slot ->
            add(UiButton(UiAction.ClearSlot(i), clearSlotRect(i), "CLEAR", enabled = slot != null && i >= locked))
        }
        val items = state.run.storage.items.size
        val itemsLabel = if (view.showStorage) "HIDE ITEMS" else "ITEMS ($items)"
        add(UiButton(UiAction.ToggleStorage, centeredRect(ui.PAUSE_ITEMS_Y), itemsLabel))
        add(UiButton(UiAction.OpenHangar, centeredRect(ui.BOTTOM_BUTTON_Y), "QUIT RUN"))
        add(settingsButton())
    }

    ScreenState.SHOP -> buildList {
        state.run.shop?.offers?.forEachIndexed { i, offer ->
            val canBuy = !offer.sold && state.meta.wallet.canAfford(offer.price) && state.canReceive(offer.type)
            val label = if (offer.sold) "SOLD" else "${offer.type.longLabel()}  \$${offer.price}"
            add(UiButton(UiAction.Buy(i), offerRect(i), label, enabled = canBuy, type = offer.type))
        }
        add(UiButton(UiAction.LeaveShop, centeredRect(ui.BOTTOM_BUTTON_Y), "CONTINUE"))
    }

    ScreenState.GAME_OVER -> listOf(
        UiButton(UiAction.Play, centeredRect(ui.PRIMARY_BUTTON_Y), "RETRY"),
        UiButton(UiAction.OpenHangar, centeredRect(ui.SECONDARY_BUTTON_Y), "HANGAR"),
    )

    ScreenState.VICTORY -> listOf(
        UiButton(UiAction.Play, centeredRect(ui.PRIMARY_BUTTON_Y), "PLAY AGAIN"),
        UiButton(UiAction.OpenHangar, centeredRect(ui.SECONDARY_BUTTON_Y), "HANGAR"),
    )
}

private fun slotButtons(state: GameState, rect: (Int) -> Rect): List<UiButton> =
    state.run.inventory.slots.mapIndexed { i, slot ->
        val usable = slot != null && (slot !is Slot.Gear || slot.ready)
        val current = slot is Slot.Armament && slot.weapon == state.run.weapon
        UiButton(UiAction.ActivateSlot(i), rect(i), slot?.label() ?: "", enabled = usable, type = slot?.type, selected = current)
    }
