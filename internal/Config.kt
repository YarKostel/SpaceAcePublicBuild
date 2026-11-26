package com.spaceace.game

import com.badlogic.gdx.Input.Keys
import com.spaceace.game.input.ClimbSettings
import com.spaceace.game.input.Control
import com.spaceace.game.input.SteeringSettings
import com.spaceace.game.logic.LootSettings
import com.spaceace.game.logic.SpawnerSettings
import com.spaceace.game.model.Field
import com.spaceace.game.model.Hitbox
import com.spaceace.game.model.MeteorSize
import com.spaceace.game.model.MeteorStats
import com.spaceace.game.model.Perk
import com.spaceace.game.model.PermanentItem
import com.spaceace.game.model.PowerUpType
import com.spaceace.game.model.ProjectileKind
import com.spaceace.game.model.ProjectileStats
import com.spaceace.game.model.Rotation
import com.spaceace.game.model.ShipStats
import com.spaceace.game.model.ShopGood
import com.spaceace.game.model.Split
import com.spaceace.game.model.StoreOption
import com.spaceace.game.model.WeaponType
import com.spaceace.game.npc.NpcSpawnerSettings
import com.spaceace.game.physics.DamageSettings
import com.spaceace.game.render.SpriteSpec
import com.spaceace.game.render.StarLayer
import com.spaceace.game.screen.FixedStepSettings
import com.spaceace.game.weapon.PierceSettings
import kotlin.math.sqrt

/** Размеры, скорости и позиции игровых объектов — в метрах и м/с (1 м = [Physics.PIXELS_PER_METER] px). */
object Config {

    object Display {
        const val VIRTUAL_WIDTH = 720f
        const val VIRTUAL_HEIGHT = 1280f
        const val TARGET_FPS = 60
        /** Сглаживание краёв (MSAA): сколько сэмплов на пиксель. */
        const val MSAA_SAMPLES_DESKTOP = 4
        const val MSAA_SAMPLES_ANDROID = 2

        // Цвет полос letterbox (вне виртуальной области).
        const val LETTERBOX_R = 0f
        const val LETTERBOX_G = 0f
        const val LETTERBOX_B = 0f

        // Цвет игрового поля.
        const val FIELD_R = 0.03f
        const val FIELD_G = 0.03f
        const val FIELD_B = 0.08f
    }

    object Physics {
        // Box2D считает в метрах; 1 м = 100 виртуальных пикселей → мир 7.2 × 12.8 м.
        const val PIXELS_PER_METER = 100f
        const val WORLD_WIDTH = Display.VIRTUAL_WIDTH / PIXELS_PER_METER
        const val WORLD_HEIGHT = Display.VIRTUAL_HEIGHT / PIXELS_PER_METER

        const val TIME_STEP = 1f / Display.TARGET_FPS
        const val MAX_FRAME_TIME = 0.25f
        const val MAX_STEPS_PER_FRAME = 5
        const val VELOCITY_ITERATIONS = 8
        const val POSITION_ITERATIONS = 3

        val STEP_SETTINGS = FixedStepSettings(
            step = TIME_STEP,
            maxFrameTime = MAX_FRAME_TIME,
            maxStepsPerFrame = MAX_STEPS_PER_FRAME,
        )

        val FIELD = Field(WORLD_WIDTH, WORLD_HEIGHT)

        // Боковые стены продолжаются выше и ниже поля: метеориты появляются над экраном и уходят под него.
        const val WALL_EXTENSION = 4f
        const val WALL_RESTITUTION = 0f
        const val FRICTION = 0.1f

        // Пробивающий снаряд (Гаусс) — сенсор: импульс цели и потерю скорости считаем сами.
        val PIERCE = PierceSettings(momentumTransfer = 0.5f, speedRetention = 0.7f, minSpeed = 4f)
    }

    object Damage {
        // damage = K × μ|v_rel|²/2 × armor; удары с энергией ниже порога (Дж) урона не наносят.
        val SETTINGS = DamageSettings(coefficient = 1f, energyThreshold = 0.5f)
    }

    /** Общее для всех кораблей игрока; параметры конкретных кораблей — в [Ships]. */
    object Player {
        // Торможение делает рулёжка (steeringImpulse), демпфирование не нужно.
        const val LINEAR_DAMPING = 0f
        /** Высота центра корабля над нижним краем поля (ниже — полоса слотов инвентаря). */
        const val START_Y = 2.2f
        /** Отступ дула от края корабля. */
        const val MUZZLE_GAP = 0.02f

        const val MAX_SPEED = 7f
        const val MAX_SPEED_Y = 5f
        val STEERING = SteeringSettings(maxSpeed = MAX_SPEED, maxSpeedY = MAX_SPEED_Y, gain = 8f, deadZone = 0.03f, maxAccel = 45f)

        /** Мягкий потолок на 2/3 поля: до босса (~10.6 м) корабль не долетает. Ниже стартовой высоты подъём не замедлен. */
        val CLIMB = ClimbSettings(fullSpeedY = START_Y, ceilingY = Physics.WORLD_HEIGHT * 2f / 3f, exponent = 1f)

        /** Пол для корабля (метеориты и снаряды проходят сквозь него): ниже — полоса слотов. */
        const val FLOOR_Y = 1.3f

        /** На сколько выше пальца держится корабль, чтобы палец его не закрывал. */
        const val FINGER_OFFSET_Y = 1.2f
    }

    object Ships {
        // Множитель входящего урона: корабль хрупче метеорита, иначе столкновения почти не ранят.
        private const val ARMOR = 2f
        private const val RESTITUTION = 0.3f
        private const val REBEL_SIDE = 0.8f

        /** Правильный треугольник основанием вниз. */
        val REBEL = ShipStats(
            hitbox = Hitbox.Triangle(base = REBEL_SIDE, height = (REBEL_SIDE * sqrt(3.0) / 2).toFloat(), rotation = Rotation.R0),
            density = 3.5f, restitution = RESTITUTION, maxHealth = 80f, armor = ARMOR,
            baseWeapon = WeaponType.MACHINE_GUN, price = 0,
            stores = listOf(
                StoreOption(PowerUpType.PerkPickup(Perk.HEAVY_ROUNDS_SUPPLY), price = 0),
                StoreOption(PowerUpType.WeaponPickup(WeaponType.RICOCHET), price = 60),
                StoreOption(PowerUpType.WeaponPickup(WeaponType.FLAK), price = 70)
            ),
        )

        /** Вытянутый вверх равнобедренный треугольник основанием вверх. */
        val GLORY = ShipStats(
            hitbox = Hitbox.Triangle(base = 0.8f, height = 1.2f, rotation = Rotation.R180),
            density = 3.5f, restitution = RESTITUTION, maxHealth = 140f, armor = ARMOR,
            baseWeapon = WeaponType.GATLING, price = 150,
            stores = listOf(
                StoreOption(PowerUpType.WeaponPickup(WeaponType.IR_MISSILES), price = 0),
                StoreOption(PowerUpType.PerkPickup(Perk.SHIELD_GENERATOR), price = 80),
                StoreOption(PowerUpType.WeaponPickup(WeaponType.LASER), price = 100),
            ),
        )

        val GREEDY = ShipStats(
            hitbox = Hitbox.Circle(radius = 0.4f),
            density = 3.5f, restitution = RESTITUTION, maxHealth = 160f, armor = ARMOR,
            baseWeapon = WeaponType.MACHINE_GUN, price = 10000,
            stores = listOf(
                StoreOption(PowerUpType.WeaponPickup(WeaponType.FLAK), price = 0),
                StoreOption(PowerUpType.WeaponPickup(WeaponType.RICOCHET), price = 200),
                StoreOption(PowerUpType.WeaponPickup(WeaponType.LASER), price = 250),
                StoreOption(PowerUpType.PerkPickup(Perk.DRONE_BAY), price = 90)
            )
        )
    }

    /** Общие числа противников; сами противники описаны в пакете content. */
    object Enemy {
        val ENABLED = true

        /** Группы противников появляются заметно реже метеоритов (у тех базовый интервал 1 с). */
        val SPAWNER = NpcSpawnerSettings(
            baseInterval = 6f, intervalJitter = 0.3f, difficultyStep = 0.35f, spawnHeight = 1f, groupSpacing = 1.1f, ambushMargin = 0.3f,
        )

        /** Строй «клин»: глубина относительно шага по ширине (крылья уходят назад под ~35°). */
        const val WEDGE_DEPTH = 0.7f

        /** Засада: ближе этого к точке входа (м) противник считается долетевшим и включает свой ИИ. */
        const val ENTRY_TOLERANCE = 0.15f

        /** Цель вне сектора обстрела: через сколько секунд проверить снова. */
        const val AIM_RETRY = 0.1f
        
        /** Movement.Stalk: за сколько обратных секунд противник выбирает отставание от нужной точки. */
        const val STALK_GAIN = 2f
    }

    object Meteor {
        private const val DENSITY = 1f
        private const val RESTITUTION = 1f
        private const val ARMOR = 1f
        val SMALL = MeteorStats(Hitbox.Circle(0.25f), density = DENSITY, restitution = RESTITUTION, maxHealth = 10f, armor = ARMOR)
        val MEDIUM = MeteorStats(Hitbox.Ellipse(0.48f, 0.34f), density = DENSITY, restitution = RESTITUTION, maxHealth = 20f, armor = ARMOR)
        val LARGE = MeteorStats(Hitbox.Circle(0.6f), density = DENSITY, restitution = RESTITUTION, maxHealth = 40f, armor = ARMOR)
        
        val SPAWNER = SpawnerSettings(
            baseInterval = 1f,
            intervalJitter = 0.3f,
            minSpeed = 1.5f,
            maxSpeed = 3f,
            maxDrift = 0.8f,
            spawnHeight = 1f,
            difficultyStep = 0.35f,
            speedStep = 0.2f,
            sizeWeights = mapOf(MeteorSize.SMALL to 0.45f, MeteorSize.MEDIUM to 0.35f, MeteorSize.LARGE to 0.2f),
        )
        
        val LOOT = LootSettings(
            fragments = mapOf(MeteorSize.LARGE to MeteorSize.SMALL),
            fragmentCount = 2..3,
            fragmentSpeed = 1.5f,
            fragmentOffset = 0.5f,
            moneyChance = 0.5f,
            money = mapOf(MeteorSize.SMALL to 1..2, MeteorSize.MEDIUM to 2..4, MeteorSize.LARGE to 4..7),
            powerUpChance = 0.08f,
        )

        val ENABLED = true
        
    }

    object Weapons {
        const val HEAVY_MASS_SCALE = 2f

        object MachineGun {
            const val INTERVAL = 0.2f
            const val SPEED = 16f
            const val SPREAD_DEG = 3f
        }

        object Gatling {
            const val INTERVAL = 0.06f
            const val SPEED = 9f
            const val SPREAD_DEG = 9f
        }

        object Gauss {
            const val MIN_CHARGE = 0.2f
            const val MAX_CHARGE = 1.2f
            const val COOLDOWN = 0.3f
            const val MIN_SPEED = 10f
            const val MAX_SPEED = 24f
        }

        object Ricochet {
            const val INTERVAL = 0.35f
            const val SPEED = 12f
            const val MAX_AIM_ANGLE_DEG = 60f
        }

        object IrMissiles {
            /** Время зарядки до выстрела. */
            const val CHARGE = 0.6f
            const val COOLDOWN = 3.5f
            const val SALVOS = 3
            const val SALVO_INTERVAL = 0.3f
            const val MISSILES_PER_SALVO = 3
            const val FAN_DEG = 12f
            const val SPEED = 13f
            const val TURN_RATE_DEG = 240f
            /** Ракета видит цели не дальше этого угла от курса: позади и сбоку не разворачивается (иначе уходит в стену). */
            const val SEEK_HALF_ANGLE_DEG = 80f
        }

        object Laser {
            /** Мощность луча, Дж/с: урон = мощность × время × броня цели. */
            const val POWER = 40f
            const val LENGTH = Physics.WORLD_HEIGHT
            const val WIDTH = 0.1f
            /** Секунд непрерывного огня до перегрева и секунд остывания от полного нагрева. */
            const val HEAT_TIME = 3f
            const val COOL_TIME = 2f
        }

        object Flak {
            const val INTERVAL = 0.5f     
            const val SPEED = 12f
            const val FUSE = 0.35f
            const val SHRAPNEL_COUNT = 8
            /** Скорость осколков относительно снаряда, м/с: у Flak игрока и у зенитки врагов — своя. */
            const val SHRAPNEL_SPEED = 10f
            const val ENEMY_SHRAPNEL_SPEED = 8f
        }
    }

    /** Саммоны: общие лимиты и числа (сами описания призыва — в content). */
    object Summon {
        /** Сколько саммонов одного вида может быть живо от одного призывателя, если в описании не сказано иное. */
        const val DEFAULT_MAX_ALIVE = 3
        /** Общий потолок саммонов на поле (союзники и вражеские вместе) — чтобы не нагружать систему. */
        const val MAX_TOTAL = 10
        /** Радиус вокруг призывателя, в котором появляются саммоны, м. */
        const val SPREAD = 0.8f
        /** Маяк дрона: через сколько секунд после гибели дрон возрождается. */
        const val BEACON_RESPAWN = 8f
        /** Movement.Escort: сколько мест на кольце сопровождения (место берётся по номеру сущности). */
        const val ESCORT_SLOTS = 6
        /** Кольцо сопровождения сплющено по вертикали: дроны держатся по бокам и над кораблём, не залезая под него. */
        const val ESCORT_FLATTEN = 0.5f
    }

    object Perks {
        const val SHIELD_GENERATOR_DURATION = 3f
        const val SHIELD_GENERATOR_COOLDOWN = 20f
        const val HEAVY_ROUNDS_SUPPLY_DURATION = 3f
        const val HEAVY_ROUNDS_SUPPLY_COOLDOWN = 20f
        const val DRONE_BAY_COOLDOWN = 25f
    }

    /** Разметка UI в виртуальных пикселях (720×1280, начало — левый нижний угол). */
    object Ui {
        const val FIRE_X = 610f
        const val FIRE_Y = 300f
        const val FIRE_RADIUS = 95f

        const val MARGIN = 20f
        const val SLOT_SIZE = 100f
        const val SLOT_GAP = 12f
        const val HUD_SLOT_Y = 20f
        const val PAUSE_BUTTON_SIZE = 64f
        const val BUTTON_WIDTH = 320f
        const val BUTTON_HEIGHT = 90f
        const val CLEAR_HEIGHT = 50f
        const val OFFER_WIDTH = 520f
        const val OFFER_HEIGHT = 110f
        const val OFFER_GAP = 30f

        // Верхняя полоса HUD.
        const val TOP_TEXT_Y = 1262f
        const val BAR_Y = 1222f
        const val BAR_HEIGHT = 24f
        const val HP_BAR_WIDTH = 200f
        const val BOSS_BAR_Y = 1184f
        const val BOSS_BAR_WIDTH = 480f
        const val EFFECTS_Y = 150f
        const val WEAPON_LABEL_Y = 180f
        const val CHARGE_BAR_WIDTH = 80f
        const val CHARGE_BAR_HEIGHT = 10f
        const val CHARGE_BAR_GAP = 14f

        // Вертикальная разметка экранов-меню.
        const val TITLE_Y = 980f
        /** Экран настроек: ряд вкладок, строки «действие — клавиша», подсказка над нижними кнопками. */
        const val SETTINGS_TAB_Y = 1060f
        const val SETTINGS_TAB_WIDTH = 240f
        const val SETTINGS_TAB_HEIGHT = 70f
        const val SETTINGS_FIRST_ROW_Y = 960f
        const val SETTINGS_ROW_HEIGHT = 62f
        const val SETTINGS_ROW_GAP = 10f
        const val SETTINGS_KEY_WIDTH = 260f
        const val SETTINGS_ROW_INSET = 60f
        const val SETTINGS_HINT_Y = 222f
        /** Шестерёнка на кнопке настроек: доли размера кнопки и число зубцов. */
        const val GEAR_RADIUS = 0.26f
        const val GEAR_HOLE = 0.11f
        const val GEAR_TOOTH = 0.13f
        const val GEAR_TEETH = 8
        const val SUBTITLE_Y = 880f
        const val INFO_Y = 820f
        const val PRIMARY_BUTTON_Y = 560f
        const val SECONDARY_BUTTON_Y = 430f
        const val HINT_Y = 300f
        const val BOTTOM_BUTTON_Y = 100f
        const val PAUSE_RESUME_Y = 820f
        const val PAUSE_SLOT_Y = 660f
        const val STORES_LABEL_GAP = 34f
        /** Подпись клавиши в углу слота (только desktop): масштаб текста и отступ от края слота, px. */
        const val KEY_HINT_SCALE = 1.3f
        const val KEY_HINT_INSET = 6f
        const val PAUSE_ITEMS_Y = 460f
        const val ITEM_LINE_HEIGHT = 34f
        const val MAX_ITEM_LINES = 6
        const val SHOP_FIRST_OFFER_Y = 700f
        const val SHOP_INVENTORY_Y = 230f

        // Ангар, снаряжение, магазин меню.
        const val SCREEN_TITLE_Y = 1200f
        const val SCREEN_MONEY_Y = 1130f
        /** Сколько карточек кораблей видно в ангаре одновременно; остальные — в слайдере. */
        const val HANGAR_VISIBLE_CARDS = 2
        const val CARD_WIDTH = 300f
        const val CARD_HEIGHT = 420f
        const val CARD_Y = 440f
        const val CARD_GAP = 40f
        const val CARD_BUTTON_Y = 340f
        const val CARD_BUTTON_HEIGHT = 80f
        const val CARD_PREVIEW_Y = 720f
        const val CARD_NAME_Y = 600f
        const val HANGAR_SHOP_Y = 200f
        const val ARROW_WIDTH = 120f
        const val PAGE_DOTS_Y = 900f
        const val PAGE_DOT_RADIUS = 6f
        const val PAGE_DOT_GAP = 26f
        /** Горизонтальный сдвиг пальца (виртуальные px), начиная с которого касание считается свайпом. */
        const val SWIPE_MIN_DISTANCE = 60f
        const val PREVIEW_SCALE = 150f
        const val CARD_PREVIEW_SCALE = 140f
        const val LOADOUT_PREVIEW_Y = 1010f
        const val LOADOUT_STATS_Y = 870f
        const val DESCRIPTION_Y = 700f
        const val DESCRIPTION_HEIGHT = 140f
        const val DESCRIPTION_WIDTH = 600f
        const val DESCRIPTION_PADDING = 12f
        /** Описание переносится по словам и ужимается от SMALL_SCALE до этого масштаба, чтобы влезть в рамку. */
        const val DESCRIPTION_MIN_SCALE = 0.9f
        const val DESCRIPTION_SCALE_STEP = 0.1f
        const val LOADOUT_STORES_LABEL_Y = 670f
        const val LOADOUT_FIRST_STORE_Y = 550f
        const val STORE_BUTTON_WIDTH = 520f
        /** Сколько вариантов подвеса видно на экране снаряжения; остальные — в слайдере. */
        const val LOADOUT_VISIBLE_STORES = 2
        const val STORE_ARROW_WIDTH = 70f
        const val STORE_BUTTON_HEIGHT = 90f
        const val LOADOUT_INVENTORY_Y = 250f
        const val HALF_BUTTON_WIDTH = 280f
        const val GOOD_BUTTON_HEIGHT = 100f
        const val GOODS_HEADER_Y = 1000f
        const val FIRST_GOOD_Y = 840f
        const val META_SHOP_NOTE_Y = 450f
        // Бестиарий: кнопка вверху ангара, сетка карточек и раскрытая карточка.
        const val HANGAR_BESTIARY_Y = 965f
        const val HANGAR_BESTIARY_HEIGHT = 70f
        const val BESTIARY_COLUMNS = 3
        /** Сколько рядов сетки видно сразу; остальные — стрелками «^»/«v» или вертикальным свайпом. */
        const val BESTIARY_ROWS = 3
        const val BESTIARY_CELL_WIDTH = 200f
        const val BESTIARY_CELL_HEIGHT = 260f
        const val BESTIARY_GAP = 20f
        /** Верхний край сетки. */
        const val BESTIARY_TOP_Y = 1090f
        /** Фигура на карточке: центр на этой доле высоты карточки, масштаб — пикселей на метр. */
        const val BESTIARY_PREVIEW_AT = 0.66f
        const val BESTIARY_PREVIEW_SCALE = 130f
        /** Наибольший размер картинки в клетке бестиария, px: крупный босс ужимается, мелкий моб не теряется. */
        const val BESTIARY_PREVIEW_MAX = 130f
        /** Подпись на карточке: высота области под названием. */
        const val BESTIARY_NAME_HEIGHT = 70f
        /** Подпись роли на карточке — над нижним краем, в долях высоты строки. */
        const val BESTIARY_ROLE_LINE = 0.8f
        const val BESTIARY_ARROW_WIDTH = 120f
        // Раскрытая карточка.
        const val ENTRY_PREVIEW_Y = 1000f
        const val ENTRY_PREVIEW_SCALE = 160f
        const val ENTRY_PREVIEW_MAX = 220f
        const val ENTRY_TITLE_Y = 860f
        const val ENTRY_TITLE_HEIGHT = 90f
        const val ENTRY_STATS_Y = 740f
        const val ENTRY_TEXT_Y = 300f
        const val ENTRY_TEXT_HEIGHT = 400f

        // Экран выбора сохранения: карточки и слайдер — как в ангаре (HANGAR_VISIBLE_CARDS, cardRect).
        const val SAVE_SLOT_TITLE_Y = 810f
        const val SAVE_SLOT_INFO_Y = 720f
        const val SAVE_DELETE_Y = 456f
        const val SAVE_DELETE_HEIGHT = 56f

        const val TITLE_SCALE = 4f
        const val TEXT_SCALE = 2f
        const val SMALL_SCALE = 1.5f
        const val BUTTON_TEXT_SCALE = 2f
        const val BUTTON_PADDING = 10f
    }

    object Inventory {
        const val SLOTS = 5
        /** Слот 1 — оружие корабля, слот 2 — подвес; подборы ложатся после них. */
        const val FIXED_SLOTS = 2
        /** Сколько первых слотов нельзя очистить: только оружие корабля (подвес очищается). */
        const val LOCKED_SLOTS = 1
        const val MAX_STACK = 9
    }

    object Shop {
        const val OFFERS = 3
        const val SHIELD_PRICE = 30
        const val REPAIR_PRICE = 25
        const val HEAVY_ROUNDS_PRICE = 30
        const val WEAPON_PRICE = 60
        const val PERK_PRICE = 60
        const val STUB_PRICE = 40
        const val DRONE_CALL_PRICE = 35
    }

    /** Магазин в меню: два прилавка товаров-заглушек. Купленное начинает выпадать в забеге. */
    object MetaShop {
        val ITEMS = listOf(
            ShopGood("ITEM 1", "IT1", 40),
            ShopGood("ITEM 2", "IT2", 60),
            ShopGood("ITEM 3", "IT3", 80),
            ShopGood("DRONE BEACON", "BCN", 150, item = PermanentItem.DroneBeacon),
        )
        val STORES = listOf(
            ShopGood("STORE 1", "ST1", 40),
            ShopGood("STORE 2", "ST2", 60),
            ShopGood("STORE 3", "ST3", 80),
        )
    }

    object Assets {
        /** Шрифт интерфейса (SIL OFL 1.1, лицензия рядом в assets/fonts/OFL.txt). */
        const val FONT = "fonts/RussoOne-Regular.ttf"
        /** Размер шрифта при масштабе текста 1, в виртуальных px — как у прежнего встроенного шрифта. */
        const val FONT_BASE_SIZE = 15f
        /** Шаг округления размера в пикселях экрана: меньше разных размеров в кэше. */
        const val FONT_SIZE_STEP = 2
        const val FONT_MIN_PIXELS = 8
    }

    /** Клавиатура (desktop). Раскладку игрок меняет в настройках; Esc не назначается — всегда пауза, «назад» и отмена. */
    object Controls {
        val DEFAULT_KEYS: Map<Control, Int> = mapOf(
            Control.FIRE to Keys.S,
            Control.SLOT_1 to Keys.A,
            Control.SLOT_2 to Keys.D,
            Control.SLOT_3 to Keys.Z,
            Control.SLOT_4 to Keys.X,
            Control.SLOT_5 to Keys.C,
            Control.MOVE_LEFT to Keys.LEFT,
            Control.MOVE_RIGHT to Keys.RIGHT,
            Control.MOVE_UP to Keys.UP,
            Control.MOVE_DOWN to Keys.DOWN,
            Control.PAUSE to Keys.P,
        )
        const val RESERVED_KEY = Keys.ESCAPE
    }

    object Save {
        /** Единственное сохранение до слотов; при первом запуске переносится в слот 1. */
        const val LEGACY_PREFERENCES_NAME = "spaceace-save"
        /** Файл слота N (с 1) — "spaceace-save-N". */
        const val SLOT_PREFIX = "spaceace-save-"
        /** Индекс: какие слоты заняты, был ли перенос старого сохранения. */
        const val INDEX_NAME = "spaceace-saves"
        const val KEY_SLOTS = "slots"
        const val KEY_MIGRATED = "legacyMigrated"
        /** Столько слотов на экране выбора, даже если они пустые; когда все заняты, добавляется ещё один. */
        const val DEFAULT_SLOTS = 3
        const val KEY_VERSION = "version"
        const val KEY_MONEY = "money"
        const val KEY_SHIPS = "ships"
        const val KEY_STORES = "stores"
        const val KEY_SELECTED_SHIP = "selectedShip"
        const val KEY_SELECTED_STORES = "selectedStores"
        const val KEY_ITEMS = "shopItems"
        const val KEY_STORE_GOODS = "shopStores"
        const val KEY_SEEN = "bestiarySeen"
        /** Формат сохранения; при изменении полей — повысить и добавить миграцию в MetaStore.load. */
        const val VERSION = 3
        /** Настройки — отдельный файл, общий для всех слотов. */
        const val SETTINGS_NAME = "spaceace-settings"
        const val SETTINGS_VERSION = 1
        const val KEY_BINDING_PREFIX = "key."
    }

    object Progression {
        /** Сколько секунд длится фаза метеоритов до появления босса. */
        const val LEVEL_DURATION = 40f
        /** Уровень, после босса которого — победа. */
        const val LAST_LEVEL = 2
    }

    object Projectile { //урон считается для скорости снаряда 15
        val BULLET = ProjectileStats(
            radius = 0.06f, density = 5f, restitution = 0f, maxBounces = 0, lifetime = 3f, piercing = false,
            breakPower = 0f,
        )
        val GATLING_ROUND = ProjectileStats(
            radius = 0.065f, density = 9f, restitution = 0f, maxBounces = 0, lifetime = 3f, piercing = false,
            breakPower = 0f,
        )
        val MISSILE = ProjectileStats(
            radius = 0.08f, density = 14f, restitution = 0f, maxBounces = 0, lifetime = 4f, piercing = false,
            homingTurnRate = Math.toRadians(Weapons.IrMissiles.TURN_RATE_DEG.toDouble()).toFloat(), breakPower = 0f,
        )
        val GAUSS_SLUG = ProjectileStats(
            radius = 0.1f, density = 8f, restitution = 0f, maxBounces = 0, lifetime = 3f, piercing = true,
            breakPower = 0f,
        )
        val RICOCHET_ORB = ProjectileStats(
            radius = 0.09f, density = 20f, restitution = 1f, maxBounces = 3, lifetime = 6f, piercing = false,
            breakPower = 0f,
        )
        val FLAK_SHELL = ProjectileStats(
            radius = 0.09f, density = 20f, restitution = 0f, maxBounces = 0, lifetime = 3f, piercing = false,
            split = flakSplit(ProjectileKind.FLAK_SHRAPNEL, Weapons.Flak.SHRAPNEL_SPEED), breakPower = 0f,
        )
        val FLAK_SHRAPNEL = ProjectileStats(
            radius = 0.035f, density = 8f, restitution = 0f, maxBounces = 0, lifetime = 0.8f, piercing = false,
            breakPower = 0f,
        )
        // Снаряды врагов по умолчанию не толкают корабль игрока; pushesPlayer = true включает толчок.
        val BOSS_BOLT = ProjectileStats(
            radius = 0.08f, density = 2.5f, restitution = 0f, maxBounces = 0, lifetime = 4f, piercing = false,
            breakPower = 0f,
        )
        val ENEMY_BOLT = ProjectileStats(
            radius = 0.07f, density = 5f, restitution = 0f, maxBounces = 0, lifetime = 4f, piercing = false,
            breakPower = 0f,
        )
        /** Зенитка врагов — числа Flak игрока, но своя скорость осколков. */
        val ENEMY_FLAK_SHELL = FLAK_SHELL.copy(split = flakSplit(ProjectileKind.ENEMY_FLAK_SHRAPNEL, Weapons.Flak.ENEMY_SHRAPNEL_SPEED))
        val ENEMY_FLAK_SHRAPNEL = FLAK_SHRAPNEL
        /** Гаусс врагов пробивает метеориты и толкает их (пробитие передаёт импульс). */
        val ENEMY_GAUSS_SLUG = GAUSS_SLUG.copy(lifetime = 2f, hitsMeteors = true)
        /** Заряд союзных дронов. */
        val ALLY_BOLT = ProjectileStats(
            radius = 0.05f, density = 5f, restitution = 0f, maxBounces = 0, lifetime = 2f, piercing = false, breakPower = 0f,
        )

        private fun flakSplit(into: ProjectileKind, speed: Float) =
            Split(fuse = Weapons.Flak.FUSE, into = into, count = Weapons.Flak.SHRAPNEL_COUNT, speed = speed)
    }

    object PowerUp {
        val HITBOX = Hitbox.Rect(0.45f, 0.45f)
        const val DENSITY = 0.5f
        const val FALL_SPEED = 1.5f

        const val SHIELD_DURATION = 8f
        /** Щит обнуляет любой урон; снаряд врага со breakPower не больше этого поглощается, сильнее — пролетает насквозь. */
        const val SHIELD_BREAK_POWER = 2f
        const val SHIELD_STACKABLE = true
        const val REPAIR_DURATION = 5f
        const val REPAIR_HEAL_PER_SECOND = 6f
        const val REPAIR_STACKABLE = true
        const val HEAVY_ROUNDS_DURATION = 10f
        const val HEAVY_ROUNDS_STACKABLE = true
        const val DRONE_CALL_STACKABLE = true
    }

    /**
     * Процедурный звёздный фон: слои от дальнего к ближнему. Ближний слой крупнее, ярче и быстрее — так выходит параллакс.
     * Звёзды едут вниз: корабль как будто летит вверх. Скорость — виртуальные пиксели в секунду.
     */
    object Stars {
        val LAYERS = listOf(
            StarLayer(count = 90, speed = 18f, size = 1.5f, brightness = 0.35f),
            StarLayer(count = 50, speed = 45f, size = 2.5f, brightness = 0.6f),
            StarLayer(count = 20, speed = 110f, size = 3.5f, brightness = 0.95f),
        )
        /** Зерно раскладки: звёзды одинаковы при каждом запуске. */
        const val SEED = 1977L
    }

    /**
     * Картинки кораблей, противников и метеоритов (assets/sprites, нос нарисован вверх).
     * size — ширина картинки относительно ширины хитбокса, turn — доворот картинки к углу тела, градусы.
     */
    object Sprites {
        val REBEL = SpriteSpec("sprites/rebel.png", size = 1.4f)
        val GLORY = SpriteSpec("sprites/glory.png", size = 1.6f)
        val GREEDY = SpriteSpec("sprites/greedy.png", size = 1.4f, pixelArt = true)
        // У Gravel треугольник повёрнут в хитбоксе (угол тела 0), у Borer — углом тела, поэтому доворот разный.
        val GRAVEL = SpriteSpec("sprites/gravel.png", size = 1.4f, turn = 180f, pixelArt = true)
        val BORER = SpriteSpec("sprites/borer.png", size = 1.7f, pixelArt = true)
        val SHARD = SpriteSpec("sprites/shard.png", size = 1.1f, turn = 180f)
        val DRONE = SpriteSpec("sprites/drone.png", size = 2.2f, pixelArt = true)
        val METEOR = SpriteSpec("sprites/meteor.png", size = 1.2f, pixelArt = true)

        /** Наибольшая скорость вращения метеорита, градусы в секунду (у каждого своя, по id). */
        const val METEOR_SPIN = 40f
        /** Затемнение картинки некупленного корабля в ангаре. */
        const val LOCKED_TINT = 0.35f
    }

    /** Цвета примитивов, RGBA8888. */
    object Render {
        const val PLAYER = 0x4FC3F7FFL
        const val METEOR = 0x9E8E7AFFL
        const val BOSS = 0xE53935FFL
        const val BULLET = 0xFFEE58FFL
        const val GATLING_ROUND = 0xFFD54FFFL
        const val MISSILE = 0xFF6E40FFL
        const val GAUSS_SLUG = 0x40C4FFFFL
        const val RICOCHET_ORB = 0xEA80FCFFL
        const val FLAK_SHELL = 0xFFCA28FFL
        const val FLAK_SHRAPNEL = 0xFFE082FFL
        const val BOSS_BOLT = 0xFF9100FFL
        const val ENEMY = 0xFF7043FFL
        const val ENEMY_BOLT = 0xFF5252FFL
        const val ENEMY_FLAK_SHELL = 0xFF8A65FFL
        const val ENEMY_FLAK_SHRAPNEL = 0xFFAB91FFL
        const val ENEMY_GAUSS_SLUG = 0xF50057FFL
        const val ALLY = 0x80DEEAFFL
        const val BEAM_PLAYER = 0x18FFFFE6L
        const val BEAM_ENEMY = 0xFF1744E6L
        /** Предупреждение луча и зоны: тусклая линия/контур; толщина луча при предупреждении — доля полной. */
        const val BEAM_WARNING = 0xFF8A8080L
        const val BEAM_WARNING_WIDTH = 0.3f
        const val ZONE_PLAYER = 0x40C4FF50L
        const val ZONE_ENEMY = 0xFF174450L
        const val ALLY_BOLT = 0xB2EBF2FFL
        const val DRONE_CALL = 0x80DEEAFFL
        const val POWER_UP = 0x69F0AEFFL
        const val SHIELD = 0x40C4FFFFL
        const val REPAIR = 0x69F0AEFFL
        const val HEAVY_ROUNDS = 0xFFAB40FFL
        const val WEAPON_PICKUP = 0xB388FFFFL
        const val PERK_PICKUP = 0x18FFFFFFL
        const val STUB = 0x9575CDFFL
        const val ITEM_PICKUP = 0xFFD740FFL
        const val DESCRIPTION_BOX = 0x1C2833E6L
        const val SLOT_SELECTED = 0xFFEE58FFL
        const val COOLDOWN_SHADE = 0x000000A0L
        const val CIRCLE_SEGMENTS = 24
        const val SHIELD_RING_GAP = 0.08f

        const val TEXT = 0xFFFFFFFFL
        const val TEXT_DIM = 0x9E9E9EFFL
        const val PANEL = 0x000000B3L
        const val BUTTON = 0x263238E6L
        const val BUTTON_DISABLED = 0x26323866L
        const val BUTTON_OUTLINE = 0x90A4AEFFL
        const val SLOT_EMPTY = 0x26323880L
        const val SLOT_FILL_ALPHA = 0.35f
        const val FIRE_FILL = 0xFFFFFF1AL
        const val FIRE_OUTLINE = 0xFFFFFF66L
        const val HP_BAR = 0x66BB6AFFL
        const val BOSS_BAR = 0xE53935FFL
        const val BAR_BACK = 0x37474FFFL
        const val CHARGE_BAR = 0xFFEE58FFL
    }

    object Debug {
        const val SHOW_OVERLAY = false
        const val FONT_SCALE = 1.2f
        const val TEXT_MARGIN = 20f
        const val TEXT_Y = 1172f
        const val FRAME_R = 0.2f
        const val FRAME_G = 0.8f
        const val FRAME_B = 0.3f
    }
}
