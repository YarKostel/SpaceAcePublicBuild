package com.spaceace.game.model

import com.spaceace.game.Config

// --- Типы и характеристики ---

data class MeteorStats(
    val hitbox: Hitbox,
    val density: Float,
    val restitution: Float,
    val maxHealth: Float,
    val armor: Float,
) {
    init {
        require(density > 0f && maxHealth > 0f && armor >= 0f) { "invalid meteor stats: $this" }
        require(restitution in 0f..1f) { "restitution out of [0; 1]: $restitution" }
    }
}

/**
 * Корабль игрока. [armor] — множитель входящего урона.
 * @property price стоимость в ангаре; 0 — открыт с самого начала.
 */
data class ShipStats(
    val hitbox: Hitbox,
    val density: Float,
    val restitution: Float,
    val maxHealth: Float,
    val armor: Float,
    val baseWeapon: WeaponType,
    val price: Int,
    /** Варианты подвеса для второго слота; первый — стартовый, открыт сразу. */
    val stores: List<StoreOption>,
) {
    init {
        require(density > 0f && maxHealth > 0f && armor >= 0f && price >= 0) { "invalid ship stats: $this" }
        require(stores.isNotEmpty() && stores.first().price == 0) { "first store option must be a free starter: $this" }
        require(restitution in 0f..1f) { "restitution out of [0; 1]: $restitution" }
    }
}

/** Вариант подвеса корабля: оружие или постоянный усилитель; [price] — стоимость открытия в меню снаряжения. */
data class StoreOption(val type: PowerUpType, val price: Int) {
    init {
        require(type is PowerUpType.WeaponPickup || type is PowerUpType.PerkPickup) { "store must be a weapon or a perk: $type" }
        require(price >= 0) { "price must be non-negative: $price" }
    }
}

/** Товар магазина в меню. Пока заглушка: эффекта нет, но купленное начинает выпадать в забеге. */
/** [item] — настоящий постоянный предмет за этим товаром прилавка ITEMS; null — заглушка без эффекта. */
data class ShopGood(val name: String, val shortName: String, val price: Int, val item: PermanentItem? = null)

/**
 * @property maxBounces сколько ударов снаряд переживает; 0 — исчезает при первом столкновении.
 * @property piercing проходит сквозь цели (сенсор), теряя скорость на каждой.
 * @property homingTurnRate самонаведение: наибольший поворот скорости, рад/с; 0 — летит прямо.
 * @property pushesPlayer толкает ли снаряд корабль игрока (обмен импульсом). false — только урон по формуле энергии.
 * @property hitsMeteors вражеский снаряд сталкивается и с метеоритами (снаряды игрока бьют метеориты всегда).
 * @property split разрыв по запалу на осколки (зенитные снаряды); null — не разрывается.
 * @property breakPower сила сбивания при встрече со снарядом другой стороны: у кого больше — уничтожает другого и летит
 *   дальше, при равной гибнут оба; у обоих 0 — не замечают друг друга. Щит игрока поглощает снаряд не сильнее себя.
 */
data class ProjectileStats(
    val radius: Float,
    val density: Float,
    val restitution: Float,
    val maxBounces: Int,
    val lifetime: Float,
    val piercing: Boolean,
    val homingTurnRate: Float = 0f,
    val pushesPlayer: Boolean = false,
    val hitsMeteors: Boolean = false,
    val split: Split? = null,
    val breakPower: Float = 0f,
) {
    init {
        require(radius > 0f && density > 0f && lifetime > 0f && maxBounces >= 0 && homingTurnRate >= 0f) {
            "invalid projectile stats: $this"
        }
        require(restitution in 0f..1f) { "restitution out of [0; 1]: $restitution" }
        require(split == null || split.fuse < lifetime) { "projectile expires before its fuse: $this" }
        require(breakPower >= 0f) { "break power must be non-negative: $this" }
    }

    val hitbox: Hitbox = Hitbox.Circle(radius)
}

/** Через [fuse] с после выстрела снаряд разрывается на [count] снарядов [into], разлетающихся со скоростью [speed]. */
data class Split(val fuse: Float, val into: ProjectileKind, val count: Int, val speed: Float) {
    init {
        require(fuse > 0f && count >= 1 && speed >= 0f) { "invalid split: $this" }
    }
}

enum class MeteorSize {
    SMALL, MEDIUM, LARGE;

    val stats: MeteorStats
        get() = when (this) {
            SMALL -> Config.Meteor.SMALL
            MEDIUM -> Config.Meteor.MEDIUM
            LARGE -> Config.Meteor.LARGE
        }
}

/** Корабли игрока. [id] — стабильное имя для файла сохранения. */
sealed interface ShipType {
    val id: String
    val stats: ShipStats

    data object Rebel : ShipType {
        override val id = "rebel"
        override val stats: ShipStats get() = Config.Ships.REBEL
    }

    data object Glory : ShipType {
        override val id = "glory"
        override val stats: ShipStats get() = Config.Ships.GLORY
    }

    data object Greedy : ShipType {
        override val id = "greedy"
        override val stats: ShipStats get() = Config.Ships.GREEDY
    }

    companion object {
        val ALL: List<ShipType> = listOf(Rebel, Glory, Greedy)
    }
}

/** Чьи снаряды: игрока или противников (боссы и вражеские корабли). */
enum class Faction { PLAYER, ENEMY }

enum class ProjectileKind(val faction: Faction) {
    BULLET(Faction.PLAYER),
    GATLING_ROUND(Faction.PLAYER),
    GAUSS_SLUG(Faction.PLAYER),
    MISSILE(Faction.PLAYER),
    RICOCHET_ORB(Faction.PLAYER),
    FLAK_SHELL(Faction.PLAYER),
    FLAK_SHRAPNEL(Faction.PLAYER),
    BOSS_BOLT(Faction.ENEMY),
    ENEMY_BOLT(Faction.ENEMY),
    ENEMY_FLAK_SHELL(Faction.ENEMY),
    ENEMY_FLAK_SHRAPNEL(Faction.ENEMY),
    ENEMY_GAUSS_SLUG(Faction.ENEMY),
    ALLY_BOLT(Faction.PLAYER);

    val stats: ProjectileStats
        get() = when (this) {
            BULLET -> Config.Projectile.BULLET
            GATLING_ROUND -> Config.Projectile.GATLING_ROUND
            GAUSS_SLUG -> Config.Projectile.GAUSS_SLUG
            MISSILE -> Config.Projectile.MISSILE
            RICOCHET_ORB -> Config.Projectile.RICOCHET_ORB
            BOSS_BOLT -> Config.Projectile.BOSS_BOLT
            ENEMY_BOLT -> Config.Projectile.ENEMY_BOLT
            FLAK_SHELL -> Config.Projectile.FLAK_SHELL
            FLAK_SHRAPNEL -> Config.Projectile.FLAK_SHRAPNEL
            ENEMY_FLAK_SHELL -> Config.Projectile.ENEMY_FLAK_SHELL
            ENEMY_FLAK_SHRAPNEL -> Config.Projectile.ENEMY_FLAK_SHRAPNEL
            ENEMY_GAUSS_SLUG -> Config.Projectile.ENEMY_GAUSS_SLUG
            ALLY_BOLT -> Config.Projectile.ALLY_BOLT
        }
}

/** Виды оружия игрока; поведение — sealed-иерархия Weapon. */
enum class WeaponType { MACHINE_GUN, GATLING, GAUSS, RICOCHET, IR_MISSILES, FLAK, LASER }

/**
 * Постоянный усилитель: не расходуется при активации, включает временный [effect] на [duration] секунд
 * и уходит на перезарядку [cooldown].
 */
/** [effect] — временный эффект при активации; null — у перка своё действие (DRONE_BAY призывает дронов). */
enum class Perk(val effect: PowerUpType.Timed?) {
    SHIELD_GENERATOR(PowerUpType.Shield),
    HEAVY_ROUNDS_SUPPLY(PowerUpType.HeavyRounds),
    DRONE_BAY(null);

    val duration: Float
        get() = when (this) {
            SHIELD_GENERATOR -> Config.Perks.SHIELD_GENERATOR_DURATION
            HEAVY_ROUNDS_SUPPLY -> Config.Perks.HEAVY_ROUNDS_SUPPLY_DURATION
            DRONE_BAY -> 0f
        }

    val cooldown: Float
        get() = when (this) {
            SHIELD_GENERATOR -> Config.Perks.SHIELD_GENERATOR_COOLDOWN
            HEAVY_ROUNDS_SUPPLY -> Config.Perks.HEAVY_ROUNDS_SUPPLY_COOLDOWN
            DRONE_BAY -> Config.Perks.DRONE_BAY_COOLDOWN
        }
}

sealed interface PowerUpType {
    /** Можно ли держать несколько штук в одном слоте инвентаря. */
    val stackable: Boolean

    /** Расходник: лежит в слоте стопкой и тратится по одному. */
    sealed interface Consumable : PowerUpType

    sealed interface Timed : Consumable {
        val duration: Float
    }

    /** Расходник «вызов дрона»: призывает союзника (описание призыва — PlayerSummons в content/Drone.kt). */
    data object DroneCall : Consumable {
        override val stackable: Boolean get() = Config.PowerUp.DRONE_CALL_STACKABLE
    }

    data object Shield : Timed {
        override val duration: Float get() = Config.PowerUp.SHIELD_DURATION
        override val stackable: Boolean get() = Config.PowerUp.SHIELD_STACKABLE
    }

    /** Лечение растянуто во времени. */
    data object Repair : Timed {
        override val duration: Float get() = Config.PowerUp.REPAIR_DURATION
        override val stackable: Boolean get() = Config.PowerUp.REPAIR_STACKABLE
    }

    data object HeavyRounds : Timed {
        override val duration: Float get() = Config.PowerUp.HEAVY_ROUNDS_DURATION
        override val stackable: Boolean get() = Config.PowerUp.HEAVY_ROUNDS_STACKABLE
    }

    /** Кладётся в слот и остаётся там; активация делает это оружие текущим. */
    data class WeaponPickup(val weapon: WeaponType) : PowerUpType {
        override val stackable: Boolean get() = false
    }

    /** Кладётся в слот и остаётся там; активация включает эффект и запускает перезарядку. */
    data class PerkPickup(val perk: Perk) : PowerUpType {
        override val stackable: Boolean get() = false
    }

    /** Заглушка подвеса с прилавка STORES магазина меню: занимает слот, эффекта нет. [index] — номер в Config.MetaShop.STORES. */
    data class StoreStub(val index: Int) : PowerUpType {
        override val stackable: Boolean get() = false
    }

    /** Постоянный предмет на поле: при подборе уходит в бесконечное хранилище, а не в слот. */
    data class ItemPickup(val item: PermanentItem) : PowerUpType {
        override val stackable: Boolean get() = false
    }
}

// --- Постоянные предметы ---

/** Постоянный предмет (хранилище). Эффекты пока не определены (открытый вопрос). */
sealed interface PermanentItem {
    /** Гарантированный дроп с босса. */
    data class Trophy(val boss: NpcKind) : PermanentItem

    /** Заглушка с прилавка ITEMS магазина меню. [index] — номер в Config.MetaShop.ITEMS. */
    data class Stub(val index: Int) : PermanentItem

    /** Маяк дрона: пока он в хранилище, рядом с кораблём есть дрон; погиб — возрождается через время. */
    data object DroneBeacon : PermanentItem
}

/** Что выпадает в забеге с самого начала; купленное в магазине меню добавляется к этому набору. */
val BASE_DROPS: List<PowerUpType> = listOf(PowerUpType.Shield, PowerUpType.Repair, PowerUpType.HeavyRounds, PowerUpType.DroneCall)

/** Все виды усилителей (для проверок полноты подписей, цен, цветов). */
val ALL_POWER_UPS: List<PowerUpType> = BASE_DROPS +
    WeaponType.entries.map { PowerUpType.WeaponPickup(it) } +
    Perk.entries.map { PowerUpType.PerkPickup(it) }
