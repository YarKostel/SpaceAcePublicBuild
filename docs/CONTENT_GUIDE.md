# SpaceAce — контент: что есть и как добавлять

Гайд по добавлению противников, боссов, усилителей и предметов. Как устроена игра в целом — `docs/ARCHITECTURE.md`. Пути — от `internal/` (тесты — рядом с кодом, `*Test.kt`).
Общее правило: числа — в `Config` (для противников — в их файле в `content/`), логика — чистые функции, проверка — тесты
(`.\gradlew.bat core:test`) и запуск через `run-debug.bat` (лог в `logs/`).

Большинство мест, которые нужно дополнить, — это исчерпывающие `when`: забыли ветку — проект не скомпилируется,
и ошибка компилятора сама покажет, куда дописать.

---

## 1. Что уже есть

### Корабли игрока (`Config.Ships`, `ShipType`)

| Корабль | Хитбокс | HP | Базовое оружие | Цена | Подвесы (2-й слот) |
|---|---|---|---|---|---|
| Rebel-class starfighter | правильный треугольник носом вверх | 80 | MACHINE_GUN | 0 | HEAVY ROUNDS SUPPLY (старт), RICOCHET $60, FLAK $70, DRONE BAY $90 |
| Glory-class assault starship | вытянутый треугольник носом вниз | 140 | GATLING | 150 | IR MISSILES (старт), SHIELD GENERATOR $80, LASER $100 |
| Greedy-class heavy cargo ship | круг | 160 | MACHINE_GUN | 0 | FLAK (старт), RICOCHET ×3 (бесплатно) |

Броня всех кораблей — множитель входящего урона ×2.

### Оружие (`WeaponType`, `weapon/Weapon.kt`, числа — `Config.Weapons`)

| Оружие | Режим огня | Снаряд | Особенность |
|---|---|---|---|
| MACHINE GUN | Auto, 0.2 с | BULLET | малый разброс 3° |
| GATLING | Auto, 0.06 с | GATLING_ROUND | разброс 9°, тяжёлый медленный снаряд |
| GAUSS CANNON | Charge 0.2–1.2 с | GAUSS_SLUG | скорость растёт с зарядом, пробивает насквозь |
| RICOCHET CANNON | SemiAuto, 0.35 с | RICOCHET_ORB | наводится на цель в секторе ±60°, 3 отскока |
| IR MISSILES | Charge 0.6 с, 3 залпа по 3 | MISSILE | самонаведение в секторе ±80° от курса, перезарядка 3.5 с |
| FLAK CANNON | SemiAuto, 0.5 с | FLAK_SHELL | через 0.35 с разрыв на 8 осколков |
| LASER | Beam (нагрев 3 с, остывание 2 с) | — (луч) | 40 Дж/с, упирается в первое препятствие |

### Противники, боссы, союзники (`content/`)

| Вид | Роль | HP | Поведение | Лут |
|---|---|---|---|---|
| `Gravel` — Gravel-Class Patrol Interceptor | MOB | 16 | держится над игроком на 4.5 м (вверх не летает), очередь из 3 пуль, только если игрок в секторе ±40° | $3–6, усилитель 15% |
| `Borer` — Mk-12 Space Borer | MOB | 16 | таран: поворачивает нос к игроку (нижняя полусфера); нос ±30° — урон ×1.5, фронт ±60° — получает ×0.5 | $4–8, усилитель 15% |
| `Shard` — Shard-Class Light Patrol Corvette | BOSS | 450 | патруль у верхнего края. Фаза 1: очереди по 10 пуль, после 2–5 очередей — 3 зенитных снаряда. Фаза 2 (HP < 50%): метеориты из-за боковых краёв, гаусс в игрока, если тот 2.5 с под боссом | $60 + трофей |
| `Pr1177` — Pr-1177 Mobile Mining Station | BOSS | — | в работе: пока только подлёт и зависание (черновик фаз — в комментарии файла), к уровням не подключён | — |
| `Drone` — Wasp Escort Drone | союзник | 20 | держится рядом с кораблём, стреляет по ближайшему врагу (нет врагов — по метеориту), закрывает от пуль | — |

### Уровни (`content/Levels.kt`)

Два уровня, второй пока повторяет первый. Уровень: 40 с метеоритов и групп противников → босс из пула (сейчас только Shard) → магазин.

| Противник | Вес | Группа | Паттерн |
|---|---|---|---|
| Gravel | 2 | 1–2 | строй ROW |
| Gravel | 1 | 2 | волна, интервал 0.8 с |
| Gravel | 1 | 1–2 | засада из-за края, 3 м/с |
| Borer | 1 | 3 | строй WEDGE (клин) |
| Borer | 1 | 2–3 | строй COLUMN |
| Borer | 1 | 2–3 | волна, интервал 0.6 с |
| Borer | 1 | 2–3 | засада, 3.5 м/с, выше по полю |

### Усилители (`PowerUpType` в `model/Types.kt`)

| Усилитель | Вид | Что делает | Где берётся |
|---|---|---|---|
| SHIELD | расходник (Timed, 8 с) | обнуляет урон; снаряды не сильнее щита (2) поглощает | выпадает, магазин $30 |
| REPAIR | расходник (Timed, 5 с) | лечит 6 HP/с | выпадает, магазин $25 |
| HEAVY ROUNDS | расходник (Timed, 10 с) | масса снарядов ×2 | выпадает, магазин $30 |
| DRONE CALL | расходник (призыв) | дрон на 20 с, не больше двух | выпадает, магазин $35 |
| SHIELD GENERATOR | постоянный (Perk) | щит 3 с, перезарядка 20 с | подвес Glory |
| HEAVY ROUNDS SUPPLY | постоянный (Perk) | тяжёлые снаряды 3 с, перезарядка 20 с | подвес Rebel (старт) |
| DRONE BAY | постоянный (Perk) | пара дронов на 15 с, перезарядка 25 с | подвес Rebel |
| оружие (WeaponPickup) | постоянный | делает это оружие текущим, из слота не пропадает | подвесы кораблей |
| STORE 1–3 (StoreStub) | заглушка | занимает слот, эффекта нет | прилавок STORES магазина меню |

### Постоянные предметы (`PermanentItem` в `model/Types.kt`) — хранилище, без слота

| Предмет | Эффект | Где берётся |
|---|---|---|
| TROPHY (`Trophy(boss)`) | нет (открытый вопрос) | гарантированно с каждого босса |
| ITEM 1–3 (`Stub(index)`) | нет (заглушка) | прилавок ITEMS магазина меню → выпадают в забеге |
| DRONE BEACON | постоянный дрон рядом с кораблём, погиб — возрождается через 8 с | прилавок ITEMS, $150 → выпадает в забеге |

### Снаряды (`ProjectileKind`, числа — `Config.Projectile`)

Игрока: BULLET, GATLING_ROUND, GAUSS_SLUG, MISSILE, RICOCHET_ORB, FLAK_SHELL, FLAK_SHRAPNEL, ALLY_BOLT (дроны).
Врагов: BOSS_BOLT, ENEMY_BOLT, ENEMY_FLAK_SHELL, ENEMY_FLAK_SHRAPNEL, ENEMY_GAUSS_SLUG (пробивает метеориты).

---

## 2. Где что живёт

| Что | Где |
|---|---|
| Вид противника/босса/союзника | `content/<Имя>.kt` — `object : NpcKind` |
| Данные поведения (блоки) | `npc/Behavior.kt` |
| Движок поведения | `npc/` (`think`, `move`, `attack`, `isMet`) |
| Какие противники и боссы на уровне | `content/Levels.kt` |
| Бестиарий (список карточек) | `content/Levels.kt` → `Roster.ALL` (враги и боссы), `Roster.ALLIES` |
| Призывы игрока | `content/Drone.kt` |
| Типы усилителей и перков | `model/Types.kt` (`PowerUpType`, `Perk`) |
| Постоянные предметы | `model/Types.kt` (`PermanentItem`, `BASE_DROPS`) |
| Слоты и их активация | `state/Inventory.kt` (`Slot`, `SlotUse`, `use`, `priceOf`) |
| Применение временных эффектов | `logic/GameLogic.kt` (`applyEffects`, `fire`) |
| Подписи в UI | `ui/UiModel.kt` (`shortLabel`, `longLabel`, `label`) |
| Цвета | `render/UiRenderer.kt` (`powerUpColor`), `render/FieldRenderer.kt` (снаряды), `Config.Render` |

---

## 3. Как добавить противника (моба)

### Шаг 1. Файл вида в `content/`

```kotlin
package com.spaceace.game.content

object Hornet : NpcKind {
    // Стабильный id: по нему сохраняется бестиарий и считаются убийства для переходов фаз. Не переименовывать.
    override val id = "hornet"
    override val title = "HORNET-CLASS SKIRMISHER"
    override val description = listOf("Fast skirmisher.", "Fires wide fans when you are below it.")
    override val role = Role.MOB

    override val body = NpcBody(
        hitbox = Hitbox.Triangle(base = 0.6f, height = 0.7f, rotation = Rotation.R180), // нос вниз
        density = 2f,
        restitution = 0.3f,
        maxHealth = 20f,
        armor = 1f,          // множитель входящего урона
        physical = true,     // true — толчки его сдвигают; false — кинематический, импульсы не действуют
        maxAccel = 8f,       // для physical: ускорение, с которым ИИ добирает нужную скорость
    )

    override val behavior = Behavior(
        BehaviorPhase(
            movement = Movement.Stalk(distance = 5f, speedX = 2.5f, descendSpeed = 1.5f, leaveSpeed = 3f),
            attacks = AttackPlan.Cycle(
                steps = listOf(
                    AttackStep(
                        Burst(ProjectileKind.ENEMY_BOLT, count = 1, interval = 0f, speed = 7f,
                            aim = Aim.AtTarget(halfAngle = degrees(60f)), fan = 5, fanStep = degrees(10f)),
                        pause = 1.5f,
                    ),
                ),
                startDelay = 1f,
            ),
        ),
    )

    override val loot = NpcLoot(money = 4..7, powerUpChance = 0.1f)
}
```

### Шаг 2. Появление на уровне — `content/Levels.kt`

```kotlin
SpawnEntry(Hornet, weight = 2f, group = 2..3, pattern = SpawnPattern.Formation(Shape.WEDGE)),
SpawnEntry(Hornet, weight = 1f, group = 1..2, pattern = SpawnPattern.Ambush(speed = 3f)),
```

Паттерны: `Formation(Shape.ROW | WEDGE | COLUMN)` — все сразу сверху; `Wave(interval)` — по одному из одной точки;
`Ambush(speed, minDepth, maxDepth, minY, maxY)` — влёт из-за бокового края. Один вид можно указать несколько раз с разными
паттернами и весами. Частота групп — `Config.Enemy.SPAWNER`.

### Шаг 3. Бестиарий — `Roster.ALL` в `content/Levels.kt`

Добавьте вид в список, иначе его карточки не будет.

### Шаг 4. Тест

По образцу `content/ContentTest.kt`: гоняйте `think(npc, sight, dt, Random(1))` в цикле и проверяйте скорости, выстрелы, фазы.

### Блоки поведения

**Движение (`Movement`)**
- `Descend(speed)` — вниз с постоянной скоростью.
- `Patrol(hoverY, speed, entrySpeed, edgeMargin, hoverGain)` — спуск на высоту и ход от края до края (как у Shard).
- `Stalk(distance, speedX, descendSpeed, leaveSpeed)` — держится над целью, вверх не летает, цель выше — уходит вниз (Gravel).
- `Escort(radius, height, speed)` — держится у призывателя (дрон).
- `Custom { input -> MoveResult(...) }` — свой код (Borer).

**Атаки (`AttackPlan`)**
- `None`.
- `Cycle(steps, pick = IN_ORDER | RANDOM, startDelay)` — шаги по кругу или случайно по весам `AttackStep.weight`.
  Шаг: `AttackStep(action, repeats = 2..5, pause = 1f)`.
- `Custom { input -> AttackResult(...) }` — свой код (Shard, фаза 2).

**Действия шага (`Action`)**
- `Burst(projectile, count, interval, speed, aim, spread, fan, fanStep, muzzle)` — очередь; каждый выстрел может быть веером.
- `Summon(SummonSpec(kind, count, maxAlive, lifetime, diesWithOwner, keepsBetweenLevels))` — призыв саммонов.
- `Laser(BeamSpec(power, length, width, warmup), duration, aim)` — луч с предупреждением; направление фиксируется при выстреле.
- `Hazard(ZoneSpec(radius, power, lifetime, affects = setOf(Faction.PLAYER), warmup), at = ZoneAt.TARGET | SELF)` — опасная зона.

**Прицел (`Aim`)**: `AtTarget(halfAngle, axis)` — в цель (у врагов — игрок), только если она в секторе; `Fixed(direction)`.

**Особые зоны корпуса (`NpcBody.zones`)**: `HitZone(halfAngle, center, takenScale, dealtScale)`, например нос-таран
`HitZone(degrees(30f), dealtScale = 1.5f)` или броня фронта `HitZone(degrees(60f), takenScale = 0.5f)`. Для зон нужен поворот
корпуса: `rotates = true`, `startAngle = PI.toFloat()` (нос вниз), угол задаёт `Movement.Custom` через `MoveResult.angle`.

**Свой код.** Состояние — свой `data class ... : Memory`, хранится в `brain.move.memory` или `brain.attack.memory`.
Выстрел из своего кода — `aimedShot(npc, kind, direction, speed)`.

### Новый снаряд для противника

1. `model/Types.kt` → `ProjectileKind`: `HORNET_NEEDLE(Faction.ENEMY)` и ветка в `stats`.
2. `Config.Projectile.HORNET_NEEDLE = ProjectileStats(radius, density, restitution, maxBounces, lifetime, piercing, …)`.
   Опции: `pushesPlayer` (толкает корабль), `hitsMeteors` (бьёт и метеориты), `split` (разрыв по запалу), `breakPower` (сила сбивания).
3. Цвет: `Config.Render.HORNET_NEEDLE` и ветка в `EntityRenderer` (компилятор подскажет).

---

## 4. Как добавить босса

То же, что противник (раздел 3), плюс:

- `role = Role.BOSS`. Полоса HP, тишина обычного спауна и победа над этапом при смерти — автоматически.
- Обычно `physical = false` (кинематическое тело: попадания и метеориты его не сдвигают).
- Несколько фаз с условием выхода:

```kotlin
override val behavior = Behavior(
    listOf(
        BehaviorPhase(movement = patrol, attacks = phaseOne, exit = Transition.HealthBelow(0.6f)),
        BehaviorPhase(
            movement = patrol,
            attacks = AttackPlan.Cycle(listOf(AttackStep(Summon(SummonSpec(Gravel, count = 2, maxAlive = 2)), pause = 4f))),
            exit = Transition.Kills(count = 4, kindId = Gravel.id), // убить 4 призванных Gravel
        ),
        BehaviorPhase(movement = patrol, attacks = phaseThree, environment = Environment.SideMeteors(meteors)),
    ),
)
override val loot = NpcLoot(money = 80..100, trophy = true)
```

- Переходы (`Transition`): `HealthBelow(доля)`, `After(секунд в фазе)`, `Kills(count, kindId или null — любые)`,
  `AnyOf(listOf(...))`, `Custom { input -> Boolean }`. Из последней фазы перехода нет.
- Окружение фазы (`Environment`): `Calm` или `SideMeteors(SideMeteorSettings(...))` — метеориты из-за боковых краёв, пока босс в этой фазе.
- Пул боссов этапа — `content/Levels.kt`: `LevelSpec(bosses = listOf(Shard, NewBoss), …)`, босс выбирается случайно.
  Новый этап — ещё один `LevelSpec` в `Levels.ALL` и `Config.Progression.LAST_LEVEL` (после босса этого уровня — победа).
- Добавьте в `Roster.ALL` для бестиария.
- Вражеские саммоны денег и лута не дают, но засчитываются для `Transition.Kills`.

---

## 5. Как добавить усилитель

### 5.1. Временный расходник (как Shield, Repair, HeavyRounds)

1. `model/Types.kt` → в `PowerUpType`:
   ```kotlin
   data object Overdrive : Timed {
       override val duration: Float get() = Config.PowerUp.OVERDRIVE_DURATION
       override val stackable: Boolean get() = Config.PowerUp.OVERDRIVE_STACKABLE
   }
   ```
   Числа — в `Config.PowerUp`.
2. Эффект — там, где он действует, через `state.run.effects.isActive(PowerUpType.Overdrive)`.
   Например, в `GameLogic.applyEffects` (корабль) или `GameLogic.fire` (оружие).
3. Подписи — `ui/UiModel.kt`: ветки в `PowerUpType.shortLabel()` (4 символа для слота) и `longLabel()`.
4. Цвет — `Config.Render.OVERDRIVE` и ветка в `powerUpColor` (`render/UiRenderer.kt`).
5. Цена в магазине между уровнями — ветка в `priceOf` (`state/Inventory.kt`) и `Config.Shop.OVERDRIVE_PRICE`.
6. Чтобы выпадал и продавался — добавьте в `BASE_DROPS` (`model/Types.kt`).

Шаги 3–5 подскажет компилятор. Слоты, стопки, продление эффекта повторной активацией работают сами.

### 5.2. Расходник со своим действием (как DRONE CALL)

1. `PowerUpType`: `data object X : Consumable { override val stackable … }`.
2. `state/Inventory.kt` → `PowerUpInventory.use`, ветка `is Slot.Supply`: что вернуть при использовании (`SlotUse`).
   Для призыва — `SlotUse.Summon(type)` и описание в `PlayerSummons.specFor` (`content/Drone.kt`).
   Для другого действия — новый вариант `SlotUse` и его обработка в `GameState.activateSlot`.
3. Подписи, цвет, цена, `BASE_DROPS` — как в 5.1.

### 5.3. Постоянный усилитель (Perk, слот с перезарядкой)

1. `model/Types.kt` → `enum class Perk`: `NEW_PERK(PowerUpType.Shield)` — какой временный эффект включает,
   или `NEW_PERK(null)` — своё действие (сейчас это призыв, через `PlayerSummons.specFor(PerkPickup(NEW_PERK))`).
2. Ветки `duration` и `cooldown` в `Perk`, числа — `Config.Perks`.
3. Подписи — `Perk.label()` и `Perk.shortLabel()` в `ui/UiModel.kt`.
4. Как получить: подвес корабля — `StoreOption(PowerUpType.PerkPickup(Perk.NEW_PERK), price = 80)` в `stores` нужного
   корабля (`Config.Ships`). Сохраняется сам как `perk:NEW_PERK`, поэтому имя в enum лучше не менять.
   Сами по себе перки не выпадают: только через подвес.

### 5.4. Оружие (усилитель-оружие)

1. `WeaponType` (`model/Types.kt`) — новое значение.
2. `weapon/Weapon.kt` — `data object` с `mode` (Auto / SemiAuto / Charge / Beam) и `shots()` (или `beam`), ветка в `Weapon.of`.
3. Числа — `Config.Weapons.<Имя>`. Сложные расчёты — `weapon/<Имя>.kt`; поведение снаряда в полёте —
   проход в `weapon/ProjectileSystem`; удар — `physics/ImpactResolver`.
4. Новый снаряд — как в разделе 3 («Новый снаряд»), но с `Faction.PLAYER`.
5. Подписи — `WeaponType.label()` и `shortLabel()` в `ui/UiModel.kt`.
6. Как получить — `StoreOption(PowerUpType.WeaponPickup(WeaponType.NEW), price = …)` в подвесах корабля
   или `baseWeapon` корабля.

---

## 6. Как добавить постоянный предмет

Постоянные предметы лежат в бесконечном хранилище забега (`RunState.storage`), слота не занимают.

1. `model/Types.kt` → в `PermanentItem`: `data object Magnet : PermanentItem` (или `data class` с параметрами).
2. Подписи — `PermanentItem.label()` и `shortLabel()` в `ui/UiModel.kt`.
3. Эффект — пассивный: в нужном месте логики проверяйте `PermanentItem.Magnet in state.run.storage.items`.
   Пример — маяк дрона в `GameLogic.summonForPlayer`.
4. Как получить:
   - **Магазин меню**: `ShopGood("MAGNET", "MAG", 120, item = PermanentItem.Magnet)` в `Config.MetaShop.ITEMS`.
     После покупки предмет начинает выпадать в забегах (`MetaState.dropPool`) и продаваться в магазине между уровнями.
     Сохраняется номер товара на прилавке, поэтому новые товары добавляйте в конец списка.
   - **С босса**: сейчас каждый босс даёт `Trophy(kind)` (`logic/GameLogic.kt → bossLoot`). Чтобы босс давал конкретный
     предмет, расширьте `NpcLoot` и `bossLoot`.

Эффекты трофеев и заглушек ITEM 1–3 пока не определены (механика пока не придумана).

---

## 7. Чек-лист после добавления

- [ ] Проект собирается: `.\gradlew.bat core:test desktop:assemble` (JAVA_HOME — JDK 21 из `jdk-path.txt`).
- [ ] Компилятор не жалуется на неполные `when`: подписи, цвета и цены есть у всех новых типов.
- [ ] Новый вид — в `Levels` (иначе не появится) и в `Roster` (иначе нет карточки в бестиарии).
- [ ] У нового вида уникальный и стабильный `id`.
- [ ] Числа в `Config` или в файле вида, без «магических чисел» в логике.
- [ ] Есть тест на новое поведение или правило.
- [ ] Проверка в игре через `run-debug.bat`; при вылете стек ошибки — в свежем `logs/run-*.log`.
