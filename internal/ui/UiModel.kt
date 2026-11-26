package com.spaceace.game.ui

import com.spaceace.game.Config
import com.spaceace.game.input.Control
import com.spaceace.game.input.KeyBindings
import com.spaceace.game.input.keyName
import com.spaceace.game.model.Perk
import com.spaceace.game.model.PermanentItem
import com.spaceace.game.model.PowerUpType
import com.spaceace.game.model.ShipType
import com.spaceace.game.model.WeaponType
import com.spaceace.game.state.Counter
import com.spaceace.game.state.GameState
import com.spaceace.game.state.MetaState
import com.spaceace.game.state.ScreenState
import com.spaceace.game.state.Slot
import kotlin.math.ceil

/** Прямоугольник в виртуальных пикселях, начало — левый нижний угол. */
data class Rect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val centerX: Float get() = x + width / 2
    val centerY: Float get() = y + height / 2

    fun contains(px: Float, py: Float) = px >= x && px <= x + width && py >= y && py <= y + height
}

sealed interface UiAction {
    /** Старт забега (из снаряжения, после game over или победы). */
    data object Play : UiAction
    data object OpenSaves : UiAction
    /** Загрузить слот или начать в пустом новую игру. */
    data class ChooseSave(val slot: Int) : UiAction
    /** Первое нажатие просит подтверждения, второе по тому же слоту удаляет. */
    data class DeleteSave(val slot: Int) : UiAction
    /** Листание слотов: +1 — к следующим, −1 — к предыдущим. */
    data class ScrollSaves(val delta: Int) : UiAction
    data object OpenHangar : UiAction
    data object OpenMetaShop : UiAction
    data object OpenBestiary : UiAction
    /** Раскрыть карточку бестиария (только встреченного вида). */
    data class OpenEntry(val kindId: String) : UiAction
    data object CloseEntry : UiAction
    /** Листание бестиария по рядам: +1 — вниз, −1 — вверх. */
    data class ScrollBestiary(val delta: Int) : UiAction
    data class SelectShip(val ship: ShipType) : UiAction
    data class BuyShip(val ship: ShipType) : UiAction
    data class PickStore(val index: Int) : UiAction
    data class BuyGood(val counter: Counter, val index: Int) : UiAction
    /** Листание ангара: +1 — к следующим кораблям, −1 — к предыдущим. */
    data class ScrollHangar(val delta: Int) : UiAction
    /** Листание подвесов: +1 — к следующим вариантам, −1 — к предыдущим. */
    data class ScrollStores(val delta: Int) : UiAction
    data object Pause : UiAction
    data object Resume : UiAction
    data object ToMenu : UiAction
    data object LeaveShop : UiAction
    data object ToggleStorage : UiAction
    data class ActivateSlot(val index: Int) : UiAction
    data class ClearSlot(val index: Int) : UiAction
    data class Buy(val index: Int) : UiAction
    data object OpenSettings : UiAction
    data object CloseSettings : UiAction
    data class OpenSettingsTab(val tab: SettingsTab) : UiAction
    /** Ждать нажатия клавиши для [control]; null — перестать ждать. */
    data class AwaitKey(val control: Control?) : UiAction
    data class Bind(val control: Control, val key: Int) : UiAction
    data object ResetControls : UiAction
}

/** Вкладки экрана настроек. */
enum class SettingsTab { CONTROLS }

/** Рисунок на кнопке вместо подписи. */
enum class UiIcon { GEAR }

/** [type] — для цвета кнопки слота или предложения; [selected] — подсветка (текущее оружие, выбранный вариант). */
data class UiButton(
    val action: UiAction,
    val rect: Rect,
    val label: String,
    val enabled: Boolean = true,
    val type: PowerUpType? = null,
    val selected: Boolean = false,
    val icon: UiIcon? = null,
)

/**
 * Локальное состояние интерфейса, не относящееся к игре.
 * @property hangarFirst индекс первой видимой карточки в слайдере ангара.
 * @property storeFirst индекс первого видимого варианта в слайдере подвесов (для корабля в меню снаряжения).
 * @property savesFirst индекс первого видимого слота на экране выбора сохранения.
 * @property confirmDelete слот, удаление которого ждёт подтверждения (повторного нажатия DELETE).
 * @property bestiaryFirst первый видимый ряд сетки бестиария; [bestiaryEntry] — id раскрытой карточки.
 * @property awaitingKey действие, которому в настройках назначается клавиша (ждём нажатия).
 */
data class UiView(
    val showStorage: Boolean = false,
    val hangarFirst: Int = 0,
    val storeFirst: Int = 0,
    val savesFirst: Int = 0,
    val confirmDelete: Int? = null,
    val bestiaryFirst: Int = 0,
    val bestiaryEntry: String? = null,
    val settingsTab: SettingsTab = SettingsTab.CONTROLS,
    val awaitingKey: Control? = null,
)

fun buttonAt(buttons: List<UiButton>, x: Float, y: Float): UiButton? =
    buttons.firstOrNull { it.enabled && it.rect.contains(x, y) }

/**
 * Применяет действие к игре и возвращает новое состояние интерфейса.
 * Недопустимые действия игнорируются — GameState сам проверяет текущий экран.
 */
fun GameState.perform(action: UiAction, view: UiView): UiView {
    when (action) {
        UiAction.Play -> startRun()
        UiAction.OpenSaves -> if (openSaves()) {
            // Сразу виден слот, в котором играли (или первый).
            val first = savesCarousel(slots.size).showing(activeSlot ?: 0, view.savesFirst)
            return view.copy(savesFirst = first, confirmDelete = null)
        }
        is UiAction.ChooseSave -> if (chooseSave(action.slot)) return hangarView(view)
        is UiAction.DeleteSave -> {
            if (view.confirmDelete != action.slot) return view.copy(confirmDelete = action.slot)
            deleteSave(action.slot)
            return view.copy(confirmDelete = null, savesFirst = savesCarousel(slots.size).clamp(view.savesFirst))
        }
        is UiAction.ScrollSaves ->
            return view.copy(savesFirst = savesCarousel(slots.size).clamp(view.savesFirst + action.delta), confirmDelete = null)
        UiAction.OpenHangar -> if (openHangar()) return hangarView(view)
        is UiAction.ScrollHangar -> return view.copy(hangarFirst = HangarCarousel.clamp(view.hangarFirst + action.delta))
        UiAction.OpenMetaShop -> openMetaShop()
        UiAction.OpenBestiary -> if (openBestiary()) return view.copy(bestiaryEntry = null, bestiaryFirst = Bestiary.carousel.clamp(view.bestiaryFirst))
        is UiAction.OpenEntry ->
            if (screen == ScreenState.BESTIARY && action.kindId in meta.seen) return view.copy(bestiaryEntry = action.kindId)
        UiAction.CloseEntry -> return view.copy(bestiaryEntry = null)
        is UiAction.ScrollBestiary -> return view.copy(bestiaryFirst = Bestiary.carousel.clamp(view.bestiaryFirst + action.delta))
        is UiAction.SelectShip -> if (openLoadout(action.ship)) {
            // В снаряжении сразу виден выбранный подвес; слайдер другого корабля не наследуется.
            val ship = meta.selectedShip
            val chosen = ship.stats.stores.indexOf(meta.storeFor(ship))
            return view.copy(storeFirst = storeCarousel(ship).showing(chosen, 0))
        }
        is UiAction.ScrollStores ->
            return view.copy(storeFirst = storeCarousel(meta.selectedShip).clamp(view.storeFirst + action.delta))
        is UiAction.BuyShip -> buyShip(action.ship)
        is UiAction.PickStore -> pickStore(action.index)
        is UiAction.BuyGood -> buyGood(action.counter, action.index)
        UiAction.Pause -> pause()
        UiAction.Resume -> resume()
        UiAction.ToMenu -> toMenu()
        UiAction.LeaveShop -> if (screen == ScreenState.SHOP) advancePhase()
        UiAction.ToggleStorage -> return view.copy(showStorage = !view.showStorage)
        is UiAction.ActivateSlot -> activateSlot(action.index)
        is UiAction.ClearSlot -> clearSlot(action.index)
        is UiAction.Buy -> buy(action.index)
        UiAction.OpenSettings -> if (openSettings()) return view.copy(settingsTab = SettingsTab.CONTROLS, awaitingKey = null)
        UiAction.CloseSettings -> closeSettings()
        is UiAction.OpenSettingsTab -> if (screen == ScreenState.SETTINGS) return view.copy(settingsTab = action.tab, awaitingKey = null)
        // Повторное нажатие на ту же клавишу-кнопку отменяет ожидание.
        is UiAction.AwaitKey -> if (screen == ScreenState.SETTINGS) {
            return view.copy(awaitingKey = action.control.takeIf { it != view.awaitingKey })
        }
        is UiAction.Bind -> rebind(action.control, action.key)
        UiAction.ResetControls -> resetControls()
    }
    // Список предметов в паузе закрывается при выходе из неё; позиция слайдера ангара помнится;
    // любое другое действие отменяет ожидающее удаление.
    return view.copy(
        showStorage = view.showStorage && screen == ScreenState.PAUSE,
        confirmDelete = null,
        bestiaryEntry = view.bestiaryEntry.takeIf { screen == ScreenState.BESTIARY },
        awaitingKey = null,
    )
}

/** В ангаре сразу видна карточка выбранного корабля. */
private fun GameState.hangarView(view: UiView): UiView {
    val selected = ShipType.ALL.indexOf(meta.selectedShip)
    return view.copy(
        showStorage = false,
        confirmDelete = null,
        bestiaryEntry = null,
        hangarFirst = HangarCarousel.showing(selected, view.hangarFirst),
    )
}

/** Строки карточки занятого слота: деньги, выбранный корабль, сколько кораблей открыто. */
fun MetaState.saveSummary(): List<String> = listOf(
    "\$${wallet.balance}",
    selectedShip.title(),
    "SHIPS ${ShipType.ALL.count(::owns)}/${ShipType.ALL.size}",
)

fun slotTitle(slot: Int) = "SLOT ${slot + 1}"

/** Подпись клавиши слота инвентаря [index] по текущей раскладке; клавиша не назначена — пусто. */
fun slotKeyHint(bindings: KeyBindings, index: Int): String =
    Control.SLOTS.getOrNull(index)?.let(bindings::keyOf)?.let(::keyName) ?: ""

fun Control.label(): String = when (this) {
    Control.FIRE -> "FIRE"
    Control.SLOT_1, Control.SLOT_2, Control.SLOT_3, Control.SLOT_4, Control.SLOT_5 -> "SLOT ${(slotIndex ?: 0) + 1}"
    Control.MOVE_LEFT -> "MOVE LEFT"
    Control.MOVE_RIGHT -> "MOVE RIGHT"
    Control.MOVE_UP -> "MOVE UP"
    Control.MOVE_DOWN -> "MOVE DOWN"
    Control.PAUSE -> "PAUSE"
}

fun SettingsTab.label(): String = when (this) {
    SettingsTab.CONTROLS -> "CONTROLS"
}

fun PowerUpType.shortLabel(): String = when (this) {
    PowerUpType.Shield -> "SHLD"
    PowerUpType.Repair -> "REP"
    PowerUpType.HeavyRounds -> "HEVY"
    PowerUpType.DroneCall -> "DRN"
    is PowerUpType.WeaponPickup -> weapon.shortLabel()
    is PowerUpType.PerkPickup -> perk.shortLabel()
    is PowerUpType.StoreStub -> Config.MetaShop.STORES[index].shortName
    is PowerUpType.ItemPickup -> item.shortLabel()
}

fun PowerUpType.longLabel(): String = when (this) {
    PowerUpType.Shield -> "SHIELD"
    PowerUpType.Repair -> "REPAIR"
    PowerUpType.HeavyRounds -> "HEAVY ROUNDS"
    PowerUpType.DroneCall -> "DRONE CALL"
    is PowerUpType.WeaponPickup -> weapon.label()
    is PowerUpType.PerkPickup -> perk.label()
    is PowerUpType.StoreStub -> Config.MetaShop.STORES[index].name
    is PowerUpType.ItemPickup -> item.label()
}

fun WeaponType.label(): String = when (this) {
    WeaponType.MACHINE_GUN -> "MACHINE GUN"
    WeaponType.GATLING -> "GATLING"
    WeaponType.GAUSS -> "GAUSS CANNON"
    WeaponType.RICOCHET -> "RICOCHET CANNON"
    WeaponType.IR_MISSILES -> "IR MISSILES"
    WeaponType.FLAK -> "FLAK CANNON"
    WeaponType.LASER -> "LASER"
}

fun WeaponType.shortLabel(): String = when (this) {
    WeaponType.MACHINE_GUN -> "MG"
    WeaponType.GATLING -> "GTLG"
    WeaponType.GAUSS -> "GAUS"
    WeaponType.RICOCHET -> "RICO"
    WeaponType.IR_MISSILES -> "IRM"
    WeaponType.FLAK -> "FLAK"
    WeaponType.LASER -> "LSR"
}

fun Perk.label(): String = when (this) {
    Perk.SHIELD_GENERATOR -> "SHIELD GENERATOR"
    Perk.HEAVY_ROUNDS_SUPPLY -> "HEAVY ROUNDS SUPPLY"
    Perk.DRONE_BAY -> "DRONE BAY"
}

fun Perk.shortLabel(): String = when (this) {
    Perk.SHIELD_GENERATOR -> "SGEN"
    Perk.HEAVY_ROUNDS_SUPPLY -> "HSUP"
    Perk.DRONE_BAY -> "DBAY"
}

/** Подпись слота: тип, количество расходников, секунды перезарядки постоянного усилителя. */
fun Slot.label(): String = when (this) {
    is Slot.Armament -> weapon.shortLabel()
    is Slot.Gear -> if (ready) perk.shortLabel() else "${perk.shortLabel()} ${ceil(cooldown).toInt()}"
    is Slot.Supply -> if (count > 1) "${type.shortLabel()} x$count" else type.shortLabel()
    is Slot.Placeholder -> type.shortLabel()
}

fun PermanentItem.label(): String = when (this) {
    is PermanentItem.Trophy -> "TROPHY: ${boss.title}"
    is PermanentItem.Stub -> Config.MetaShop.ITEMS[index].name
    PermanentItem.DroneBeacon -> "DRONE BEACON"
}

fun PermanentItem.shortLabel(): String = when (this) {
    is PermanentItem.Trophy -> "TRPH"
    is PermanentItem.Stub -> Config.MetaShop.ITEMS[index].shortName
    PermanentItem.DroneBeacon -> "BCN"
}

fun ShipType.title(): String = when (this) {
    ShipType.Rebel -> "REBEL-CLASS"
    ShipType.Glory -> "GLORY-CLASS"
    ShipType.Greedy -> "GREEDY-CLASS"
}

fun ShipType.subtitle(): String = when (this) {
    ShipType.Rebel -> "STARFIGHTER"
    ShipType.Glory -> "ASSAULT STARSHIP"
    ShipType.Greedy -> "HEAVY CARGO SHIP"
}

/** Строки для прямоугольника с описанием в меню снаряжения (встроенный шрифт не переносит слова сам). */
fun ShipType.description(): List<String> = when (this) {
    ShipType.Rebel -> listOf("Light, fast, and reliable Confederate starfighter.", "Most combat pilots start out in one of these.", "Despite its low durability, it boasts serious firepower and agility.")
    ShipType.Glory -> listOf("Confederate close air support (CAS) starship.", "Its rugged hull is designed to withstand prolonged fire from anti-air guns.", "It is equipped with a powerful Gatling autocannon and reliable hardpoints for heavy IR missiles or additional shields.")
    ShipType.Greedy -> listOf("Heavy cargo starship.","Quite popular among smugglers and pirates, many of whom readily go freelance for the Confederate Army.", "Slow and unwieldy, it boasts impressive durability and highly specialized boarding weaponry.")
}
