package com.spaceace.game.state

import com.spaceace.game.Config
import com.spaceace.game.model.Perk
import com.spaceace.game.model.PowerUpType
import com.spaceace.game.model.WeaponType
import kotlin.random.Random

/** Что лежит в слоте инвентаря. [type] — для подписи и цвета. */
sealed interface Slot {
    val type: PowerUpType

    /** Оружие: не расходуется, активация делает его текущим. */
    data class Armament(val weapon: WeaponType) : Slot {
        override val type: PowerUpType get() = PowerUpType.WeaponPickup(weapon)
    }

    /** Постоянный усилитель: не расходуется, после активации — перезарядка [cooldown] секунд. */
    data class Gear(val perk: Perk, val cooldown: Float = 0f) : Slot {
        override val type: PowerUpType get() = PowerUpType.PerkPickup(perk)
        val ready: Boolean get() = cooldown <= 0f
    }

    /** Заглушка подвеса из магазина меню: занимает слот, активировать нечего. */
    data class Placeholder(override val type: PowerUpType.StoreStub) : Slot

    /** Расходник: временный эффект или призыв, тратится по одному. */
    data class Supply(override val type: PowerUpType.Consumable, val count: Int = 1) : Slot {
        init {
            require(count >= 1) { "supply count must be positive: $count" }
        }
    }

    companion object {
        /** null — предмет не кладётся в слот (постоянные предметы идут в хранилище). */
        fun of(type: PowerUpType): Slot? = when (type) {
            is PowerUpType.Consumable -> Supply(type)
            is PowerUpType.WeaponPickup -> Armament(type.weapon)
            is PowerUpType.PerkPickup -> Gear(type.perk)
            is PowerUpType.StoreStub -> Placeholder(type)
            is PowerUpType.ItemPickup -> null
        }
    }
}

/** Результат активации слота. */
sealed interface SlotUse {
    data class Equip(val weapon: WeaponType) : SlotUse
    data class Effect(val type: PowerUpType.Timed, val duration: Float) : SlotUse

    /** Призыв: что именно призвать, решает PlayerSummons (content/Drone.kt) по источнику [source]. */
    data class Summon(val source: PowerUpType) : SlotUse
}

/**
 * Слоты усилителей. Первые [fixedSlots] заняты снаряжением корабля (оружие по умолчанию и подвес),
 * первые [lockedSlots] из них нельзя очистить. Все методы возвращают новый инвентарь; null — операция невозможна.
 */
data class PowerUpInventory(
    val slots: List<Slot?>,
    val fixedSlots: Int = Config.Inventory.FIXED_SLOTS,
    val lockedSlots: Int = Config.Inventory.LOCKED_SLOTS,
    val maxStack: Int = Config.Inventory.MAX_STACK,
) {
    init {
        require(slots.isNotEmpty() && fixedSlots in 0..slots.size && lockedSlots in 0..fixedSlots && maxStack >= 1) {
            "invalid inventory: $this"
        }
    }

    /**
     * Расходник докладывается в слот того же типа, иначе занимает первый пустой.
     * Оружие и постоянный усилитель, которые уже есть, повторно не занимают места — подбор просто поглощается.
     */
    fun add(type: PowerUpType): PowerUpInventory? {
        val slot = Slot.of(type) ?: return null
        if (type is PowerUpType.Consumable) {
            val stack = slots.indexOfFirst { it is Slot.Supply && it.type == type && it.count < maxStack }
            if (stack >= 0) return withSlot(stack, (slots[stack] as Slot.Supply).let { it.copy(count = it.count + 1) })
        } else if (slots.any { it?.type == type }) {
            return this
        }
        val empty = slots.indexOf(null)
        return if (empty >= 0) withSlot(empty, slot) else null
    }

    /** Активация слота; null — слот пуст или постоянный усилитель ещё перезаряжается. */
    fun use(index: Int): Pair<PowerUpInventory, SlotUse>? = when (val slot = slots.getOrNull(index)) {
        null, is Slot.Placeholder -> null
        is Slot.Armament -> this to SlotUse.Equip(slot.weapon)
        is Slot.Gear ->
            if (!slot.ready) null
            else withSlot(index, slot.copy(cooldown = slot.perk.cooldown)) to
                (slot.perk.effect?.let { SlotUse.Effect(it, slot.perk.duration) } ?: SlotUse.Summon(slot.type))
        is Slot.Supply -> {
            val rest = if (slot.count > 1) slot.copy(count = slot.count - 1) else null
            val use = when (val type = slot.type) {
                is PowerUpType.Timed -> SlotUse.Effect(type, type.duration)
                PowerUpType.DroneCall -> SlotUse.Summon(type)
            }
            withSlot(index, rest) to use
        }
    }

    fun clear(index: Int): PowerUpInventory? =
        if (index < lockedSlots || slots.getOrNull(index) == null) null else withSlot(index, null)

    /** Перезарядка постоянных усилителей. */
    fun tick(dt: Float): PowerUpInventory {
        if (slots.none { it is Slot.Gear && !it.ready }) return this
        return copy(slots = slots.map { if (it is Slot.Gear && !it.ready) it.copy(cooldown = (it.cooldown - dt).coerceAtLeast(0f)) else it })
    }

    private fun withSlot(index: Int, slot: Slot?) = copy(slots = slots.toMutableList().also { it[index] = slot })

    companion object {
        fun empty(size: Int = Config.Inventory.SLOTS) = PowerUpInventory(List(size) { null })

        /** Слот 1 — оружие корабля по умолчанию, слот 2 — выбранный подвес, остальные пусты. */
        fun loadout(baseWeapon: WeaponType, store: PowerUpType, size: Int = Config.Inventory.SLOTS): PowerUpInventory {
            val slots = MutableList<Slot?>(size) { null }
            slots[0] = Slot.Armament(baseWeapon)
            slots[1] = requireNotNull(Slot.of(store)) { "store must fit a slot: $store" }
            return PowerUpInventory(slots)
        }
    }
}

/** Оставшееся время временных эффектов. Повторная активация продлевает. */
data class ActiveEffects(val remaining: Map<PowerUpType.Timed, Float> = emptyMap()) {
    fun activate(type: PowerUpType.Timed, duration: Float = type.duration) =
        copy(remaining = remaining + (type to (remaining[type] ?: 0f) + duration))

    fun tick(dt: Float): ActiveEffects =
        if (remaining.isEmpty()) this else copy(remaining = remaining.mapValues { it.value - dt }.filterValues { it > 0f })

    fun isActive(type: PowerUpType.Timed) = type in remaining

    fun timeLeft(type: PowerUpType.Timed) = remaining[type] ?: 0f
}

data class ShopOffer(val type: PowerUpType, val price: Int, val sold: Boolean = false)

data class Shop(val offers: List<ShopOffer>) {
    fun sold(index: Int) = copy(offers = offers.mapIndexed { i, offer -> if (i == index) offer.copy(sold = true) else offer })

    companion object {
        /** [count] разных случайных усилителей из каталога. */
        fun generate(catalog: List<PowerUpType>, count: Int, random: Random) =
            Shop(catalog.distinct().shuffled(random).take(count).map { ShopOffer(it, priceOf(it)) })
    }
}

fun priceOf(type: PowerUpType): Int = when (type) {
    PowerUpType.Shield -> Config.Shop.SHIELD_PRICE
    PowerUpType.Repair -> Config.Shop.REPAIR_PRICE
    PowerUpType.HeavyRounds -> Config.Shop.HEAVY_ROUNDS_PRICE
    is PowerUpType.WeaponPickup -> Config.Shop.WEAPON_PRICE
    is PowerUpType.PerkPickup -> Config.Shop.PERK_PRICE
    PowerUpType.DroneCall -> Config.Shop.DRONE_CALL_PRICE
    is PowerUpType.StoreStub, is PowerUpType.ItemPickup -> Config.Shop.STUB_PRICE
}
