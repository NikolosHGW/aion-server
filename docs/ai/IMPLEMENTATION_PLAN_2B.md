# Этап 2B: read-only планировщик одной квестовой цели

Статус: принят вручную владельцем проекта 2026-08-01

Дата проектирования: 2026-08-01

Зависимости:

- [ADR-0001](ADR-0001-SERVER-CONTROLLED-PLAYER.md) принят;
- [этап 2A](IMPLEMENTATION_PLAN_2A.md) принят владельцем проекта после ручной
  проверки двумя клиентами;
- [ADR-0002](ADR-0002-READ-ONLY-QUEST-GOAL-PLANNING.md) принят владельцем
  проекта 2026-08-01.

Реализация сохраняет SQL/schema, игровые данные и Java quest handlers без
изменений. Этап 2B ограничен read-only runtime-планированием и принят вручную
после повторной проверки исправления ложного `STALE_OFFER`.

## 0. Результат реализации 2026-08-01

- Добавлен отдельный `FULL_SILENT_READ_ONLY` entry point в `QuestService` и
  полная тихая проверка equipment в `XMLStartCondition`; существующие overloads
  и их историческая `warn=false` семантика не изменены.
- Provider принимает только configured allowlist (пустой по умолчанию, максимум
  три уникальных положительных ID), exact `MonsterHuntData`/`MonsterHunt` и
  fail-closed форму template. Automatic discovery отсутствует.
- Quest `1102` проверен как первый эксплуатационный fixture: Elyos, Poeta,
  prerequisite `1101`, start/end NPC `203057`, kill `210133|210134 x3`, static
  spawns и non-instance map существуют; Java handler для ID отсутствует.
- Planner получает только immutable candidate assessments и детерминированно
  сортирует по level difference, squared start distance, objective count и ID.
- `CompanionGoalSession` привязан к exact owner/companion object IDs. `today`
  записывает только `offered`, `goal choose` повторно строит план и записывает
  только `chosen`; clear/removal/runtime-disable очищают оба значения.
- Отдельная scheduler registration или задача для goal session не добавлена:
  runtime-disable maintenance использует существующий общий companion tick.
- `PlayerActionGateway`, SQL/schema, static game data и quest handlers не
  изменены. Quest dialog/start/finish, packets, inventory/reward и DAO paths не
  вызываются.
- Полный Docker/JDK 25 Maven reactor завершён `BUILD SUCCESS`: game-server 83
  теста и commons 1 тест, всего 84; failures/errors/skips — 0/0/0.

### 0.1. Исправление ложного `STALE_OFFER` 2026-08-01

Первая ручная приёмка выявила воспроизводимый ложный `STALE_OFFER` между
успешным `today` и `goal choose`. Причина — сравнение полных records
`QuestGoalPlan.equals()`: value-based equality была реализована автоматически,
identity comparison не использовался, но в equality входили
`distanceToStartSquared`, `selectionReason`, formatter/presentation strings и
все `QuestGoalStep`, включая выбранный `ReferenceCoordinate`.

В фактическом call-chain поле `selectionReason` отличалось гарантированно:
offered после `QuestGoalPlanner.propose()` содержал
`full silent eligibility passed; current non-instance map; deterministic level/distance/count/questId score`,
а rebuilt напрямую из provider содержал `validated allowlist candidate`.
Quest ID и структурная семантика шагов при этом совпадали. Координаты owner в
плане напрямую не хранились, однако вычисленная от них distance могла
дополнительно меняться при движении; при смене ближайшего static spawn менялся
и reference. Timestamp, mutable collections и нестабильный hash iteration
причиной не были: DTO копируют collections, а spawn selection имеет полный
deterministic tie-break. В incident-лог не попадали оба плана, поэтому старые
числовые значения distance/reference восстановить невозможно.

Исправление:

- `QuestGoalPlan.semanticFingerprint()` сравнивает только quest ID, minimum
  level, отсортированные prerequisite IDs и ordered steps
  (`type`, ordered target NPC IDs, required count). Start/end NPC IDs входят
  через соответствующие step targets.
- Distance, owner position, localized/raw/reason/formatter text, rewards и
  limitations, static ID и reference coordinate не входят в fingerprint.
  Rewards в 2B описательны и не исполняются; eligibility повторно проверяется
  отдельно.
- `choose` проверяет exact owner/companion IDs, наличие offer, текущие flags,
  присутствие exact offered quest ID в allowlist, candidate shape/map и
  read-only eligibility. При успехе chosen становится заново построенный
  актуальный immutable plan.
- Ожидаемые результаты представлены `ChoiceResult`: `CHOSEN`, `NO_OFFER`,
  `STALE_OFFER`, `INELIGIBLE`, `SESSION_MISMATCH`. Они форматируются admin
  command без exception. Настоящий stale очищает offer/chosen и пишет
  `AI_COMPANION_GOAL action=STALE` с quest ID и точным reason code.
- Добавлены regression tests немедленного и повторного offer/choose,
  value-equivalent DTO, движения/distance/text/reference-spawn изменений,
  настоящего semantic stale, принятого owner квеста, no-offer, identity
  mismatch и форматирования всех ожидаемых исходов без exception.
- После исправления узкий Docker/JDK 25 reactor: 43 теста; полный reactor:
  game-server 90 тестов и commons 1 тест, всего 91. Во всех запусках
  failures/errors/skips — 0/0/0, `BUILD SUCCESS`.

Java-код исправления прошёл повторную ручную проверку владельцем проекта
2026-08-01.

### 0.2. Результат повторной ручной приёмки 2026-08-01

Владелец проекта подтвердил двумя клиентами:

- `//companion today` предлагает quest `1102`, а повторный `today`
  возвращает тот же план;
- после перемещения владельца `//companion goal choose` успешно сохраняет
  runtime chosen plan и отвечает `runtime AI goal only; quest not accepted`;
- goal status показывает `offeredQuestId=1102` и `chosenQuestId=1102`;
- лог содержит `AI_COMPANION_GOAL action=CHOSEN`, необработанного
  `Exception executing chat command` нет;
- quest `1102` не появился в `player_quests`, игровой journal владельца не
  изменился, persistence отсутствует;
- companion продолжает штатное поведение принятого этапа 2A; регрессий этапов
  1 и 2A в проверенном сценарии не обнаружено.

Граница принятия: подтверждены только read-only предложение, повторная
валидация и runtime-выбор одной квестовой цели. Принятие не распространяется
на выполнение квестов, navigation, combat, groups, loot, rewards, economy или
persistence.

## 1. Цель и строгая граница

Один real connected owner с активным companion этапа 2A выполняет:

```text
//companion today
//companion goal choose
//companion goal status
//companion goal clear
```

`today` выдаёт один существующий, доступный сейчас и безопасно разобранный
quest plan. `choose` повторно валидирует предложение и записывает только
runtime goal текущего companion. Это не принятие квеста в journal.

В 2B не входят:

- вызов dialog/start/end quest handlers и изменение `QuestState`;
- принятие, выполнение или завершение квеста;
- движение к NPC/целям и автоматическое взаимодействие;
- combat, skills, kills и groups;
- collect, loot, inventory mutations, items, rewards и XP;
- economy, LLM, persistence, schema или новые game-data entries;
- repeatable/daily/weekly/faction/event/test/instance quests;
- автоматический поиск по всему каталогу и произвольные Java quest scripts;
- background progression или offline goal session.

## 2. Подтверждённая база этапа 2A

Ручная приёмка 2026-08-01 доказала player presentation, single-companion
lifecycle, плавные follow/stay, LoS/collision blocking, late visibility,
cleanup при map/instance/logout/dismiss/runtime-disable, безопасную коллизию с
`//aiplayer` и отсутствие изменений template character/account. Это позволяет
привязать read-only session к существующему companion.

Проверка не распространяется на quest, inventory, combat или persistence.
Этап 2B не должен интерпретировать её как разрешение передавать companion в
штатный quest flow.

## 3. Аудит quest data и scripts

### 3.1. Источники и загрузка

| Данные | Точка репозитория | Что доступно |
|---|---|---|
| Основные templates | [`quest_data.xml`](../../game-server/data/static_data/quest_data/quest_data.xml), схема [`quest_data.xsd`](../../game-server/data/static_data/quest_data/quest_data.xsd#L13) | ID/name/nameId, ограничения, prerequisites, kill/collect metadata, rewards. |
| Runtime templates | [`QuestsData`](../../game-server/src/com/aionemu/gameserver/dataholders/QuestsData.java#L18), [`QuestTemplate`](../../game-server/src/com/aionemu/gameserver/model/templates/QuestTemplate.java#L37) | `getQuestById()` и read-only collection всех templates. |
| XML script metadata | [`quest_script_data`](../../game-server/data/static_data/quest_script_data), схема [`quest_script_data.xsd`](../../game-server/data/static_data/quest_script_data/quest_script_data.xsd) | Тип handler и start/end NPC для стандартных шаблонов; structured XML events для `xml_quest`. |
| Runtime XML metadata | [`XMLQuests`](../../game-server/src/com/aionemu/gameserver/dataholders/XMLQuests.java#L18), [`XMLQuest`](../../game-server/src/com/aionemu/gameserver/questEngine/handlers/models/XMLQuest.java#L21) | Lookup по quest ID; фактический subtype (`MonsterHuntData`, `ItemCollectingData`, ...). |
| Java scripts | [`data/handlers/quest`](../../game-server/data/handlers/quest) | Произвольная Java-логика `onDialogEvent`, kill/talk/zone/item hooks. Безопасно структурно разобрать в общем случае нельзя. |
| Регистрация | [`QuestEngine.init()`](../../game-server/src/com/aionemu/gameserver/questEngine/QuestEngine.java#L85) | Загружает Java handlers, затем регистрирует `DataManager.XML_QUESTS`. |

### 3.2. Представление требуемых полей

| Поле | Фактическое представление и ограничение |
|---|---|
| Quest ID | `QuestTemplate.id`; тот же ID связывает template, XML metadata и handler. |
| Название | `name` — английская диагностическая строка; `nameId`/`getL10n()` — marker клиентской локализации ([`QuestTemplate.getL10nId()`](../../game-server/src/com/aionemu/gameserver/model/templates/QuestTemplate.java#L248)). |
| Описание | В `QuestTemplate` и XSD отсутствует description/description ID. Полный русский текст живёт в клиентских данных; сервер может показать localized quest link, но не достоверно извлечь описание. |
| Start/end NPC | Для стандартного `monster_hunt` — `MonsterHuntData.startNpcIds/endNpcIds`; если end отсутствует, handler использует start NPC ([`MonsterHunt` constructor](../../game-server/src/com/aionemu/gameserver/questEngine/handlers/template/MonsterHunt.java#L50)). В Java scripts значения находятся в коде. |
| Level/race/class/gender/rank | Поля `QuestTemplate`; проверяются в [`QuestService.checkStartConditions()`](../../game-server/src/com/aionemu/gameserver/services/QuestService.java#L302). |
| Prerequisites | `XMLStartCondition`: finished/unfinished/noacquired/acquired/equipped/title. `finished` может требовать конкретную reward group ([`XMLStartCondition`](../../game-server/src/com/aionemu/gameserver/model/templates/quest/XMLStartCondition.java#L27)). |
| Repeat/daily | `maxRepeatCount`, `QuestState.completeCount`, `nextRepeatTime`, `QuestRepeatCycle`; [`QuestState.canRepeat()`](../../game-server/src/com/aionemu/gameserver/questEngine/model/QuestState.java#L121) читает их и текущее время. Первый 2B их исключает. |
| Map/coordinates | XML attribute `quest_zone` присутствует в XSD, но не mapped в `QuestTemplate`, поэтому runtime его теряет. Координаты берутся из static spawns через [`SpawnsData.getSpawnsForNpc()`](../../game-server/src/com/aionemu/gameserver/dataholders/SpawnsData.java#L179) и `SpawnTemplate`; у одного NPC может быть несколько maps/spawns. |
| Kill objective | `QuestTemplate.getQuestKill()` -> `QuestKill`: step/var/sequence, NPC IDs и count. `MonsterHuntData.register()` превращает их в handler `Monster` ([`MonsterHuntData`](../../game-server/src/com/aionemu/gameserver/questEngine/handlers/models/MonsterHuntData.java#L59)). |
| Collect objective | `CollectItems`, `QuestDrop` и `ItemCollectingData`; соответствие drop/item/step может быть сложным. Не входит в первый 2B. |
| Talk objective | Start/end NPC стандартных handlers либо event/operation graph `XmlQuestData`; Java scripts могут ветвиться произвольно. В 2B используются только start и final report как описательные шаги. |
| Количество | `QuestKill.count`/`CollectItem.count`; для multi-var и data-driven сценариев семантика зависит от handler. Первый 2B принимает только положительные простые `quest_kill` шага 0. |
| Rewards | `QuestTemplate.getRewards()`, extended/class/selectable rewards; [`Rewards`](../../game-server/src/com/aionemu/gameserver/model/templates/quest/Rewards.java#L10) содержит kinah/XP/AP/DP/GP/title/expansion/items. Formatter только описывает данные. |
| NPC/item names | `NpcTemplate`/`ItemTemplate` реализуют L10n; `ChatUtil.path()`, `itemName()` и client markers локализуются самим русским клиентом. |

Примеры доказуемо структурированных candidates: Elyos `1102` в
[`poeta.xml`](../../game-server/data/static_data/quest_script_data/poeta.xml)
с kill metadata в `quest_data.xml`, Asmodian `2102` в
[`ishalgen.xml`](../../game-server/data/static_data/quest_script_data/ishalgen.xml).
Это кандидаты для верификации, а не зашитый список: фактический первый ID надо
подтвердить под race/level/map тестового owner.

### 3.3. Что нужно читать у owner

Только уже загруженные runtime-данные реального владельца:

- exact object identity в `World`, spawned/map/instance и наличие
  `AionConnection`;
- level, race, class, gender, abyss rank и permissions;
- `QuestStateList`: status, complete count, reward group, next repeat time;
- title/equipment/inventory presence, skill levels и active NPC factions,
  только если template содержит соответствующее условие;
- текущая позиция для детерминированного выбора ближайшего static spawn.

Read methods сами по себе не должны менять persistent state. До и после
planning тесты сравнивают journal, inventory, XP и packet counters. AI-body не
используется для eligibility: квест предлагается connected owner.

## 4. Eligibility call-chain audit

| Метод | Вход / результат | Побочные эффекты и connection | Решение для 2B |
|---|---|---|---|
| `QuestsData.getQuestById/getQuestTemplates` | ID / template или collection | Нет player/packet/DB side effects. Collection и templates mutable по типу. | Использовать, сразу копировать нужные значения в DTO. |
| `XMLQuests.getQuest` | ID / `XMLQuest` subtype | Нет runtime side effects. Поля start/end не имеют public getters. | Использовать после узких read-only getters в `MonsterHuntData`. |
| `QuestEngine.isHaveHandler` | ID / boolean | Только read map. | Использовать как дополнительную startup/runtime validation. |
| `QuestEngine.getQuestNpc` | NPC ID / registration view | При отсутствии создаёт новый detached `QuestNpc`; reverse lookup отсутствует. | Не использовать для извлечения start/end; metadata является source of truth. |
| `QuestTemplate` getters | template fields | Обычно read-only. `getRewards()` возвращает существующие элементы. | Defensive copy; не изменять lists. |
| `QuestKill.getNpcIds` | нет / list NPC IDs | Скрытая однократная нормализация: переносит JAXB list, очищает и null-ит исходное поле ([метод](../../game-server/src/com/aionemu/gameserver/model/templates/quest/QuestKill.java#L59)). Не меняет Player/DB, но не является строго pure. | QuestEngine уже вызывает его при startup; planner получает immutable copy. Добавить regression test на повторный read. |
| `QuestStateList.getQuestState` | ID / live state | Getter без мутации, но возвращает mutable объект. | Только snapshot значений; никаких setters. |
| `QuestState.canRepeat` | state + server time / boolean | Читает template/time, без packet/connection/DB. | Аудирован, но первый 2B исключает repeatable/time-based quests. |
| `XMLStartCondition.check(player, warn)` | Player + feedback flag / boolean | При `warn=true` может отправить packet; при `warn=false` **пропускает equipment condition**, поэтому это не полная тихая eligibility. | Нужен безопасный overload, который проверяет equipment при отключённом feedback. |
| `QuestService.inventoryItemCheck(env, false)` | Player/template / boolean | При `false` только читает inventory; packet только при `true`. Не зависит от `isOnline()`. | Переиспользовать внутри нового read-only entry point. |
| `QuestService.checkCombineSkill(env, false)` | Player skills/template / boolean | При `false` только читает skills; packet только при `true`. | Переиспользовать. |
| `NpcFactions.canStartQuest/getFactionById` | template/ID / boolean/state | Указанные getters read-only; `sendDailyQuest/startQuest/set*` мутируют. | Читать только для allowlisted non-faction не потребуется; faction candidates запрещены. |
| `QuestService.checkStartConditions(..., false)` | Player + ID / boolean | Для audited branch нет packet/QuestState mutation и нет `isOnline()`, но equipment semantics неполна; не проверяет quest-log capacity и handler-specific logic. | Не считать самостоятельной полной гарантией. Вынести новый read-only entry point без изменения старых overloads. |
| `QuestService.checkLevelRequirement` | template/level / boolean | Pure. | Можно использовать в validation/tests; итог решает общий entry point. |
| `QuestService.startQuest` | `QuestEnv` / boolean | Проверяет capacity, затем создаёт/меняет `QuestState`, faction/challenge state, отправляет `SM_QUEST_ACTION`, обновляет nearby quests. | Строго запрещён. |
| `QuestEngine.onDialog` | `QuestEnv` / handled | Исполняет произвольный handler и отправляет packets; handler может вызвать start/reward/item operations. | Строго запрещён; packet simulation не допускается. |
| `MonsterHunt.onDialogEvent/onKillEvent` | `QuestEnv` / boolean | Dialog packets, startQuest, QuestState vars/status, item removal. | Использовать класс только как доказательство семантики metadata, никогда не вызывать. |
| `QuestService.finishQuest/addOrUpdateQuest/collectItemCheck(remove=true)` | quest/player / result | QuestState, items, rewards, XP, packets. | Строго запрещены. |
| `SpawnsData.getSpawnsForNpc` | map + NPC ID / spawn groups | Читает static data. Returned lists/templates не immutable. | Копировать координаты; reject отсутствующие/неоднозначные данные. |
| `QuestTemplate.getL10n`, `ChatUtil.quest/path/itemName` | L10n ID/entity ID / encoded marker | Pure encoding. Русскую строку раскрывает client, не server. | Использовать в formatter connected owner; fallback raw name + ID. |
| `PacketSendUtility.sendMessage` | connected owner + text | Отправляет `SM_MESSAGE` только если `Player.isOnline()`; quest state не меняет. | Разрешён только command formatter для реального owner, не для companion. |
| `PlayerQuestListDAO.store` | Player | Пишет `player_quests`. | Не импортировать и не вызывать; source-boundary test. |

Новый entry point должен возвращать структурированный `EligibilityResult`
(eligible либо стабильный reason code) и проверить всё, что реально блокирует
обычный `startQuest`, включая quest-log capacity и equipment, не посылая
предупреждения. Он не вызывает handler. Для allowlisted стандартного
`monster_hunt` handler-specific start path ограничен заранее проверенной формой.

## 5. Рекомендуемая архитектура

```text
//companion today
  -> CompanionService: exact active owner + companion lifecycle
  -> QuestGoalCandidateProvider: allowlist + immutable metadata/spawn DTO
  -> ReadOnlyQuestEligibility: full quiet checks, no dialog/start
  -> QuestGoalPlanner: deterministic filter/score/explain
  -> CompanionGoalSession: offered plan (runtime only)
  -> QuestGoalCommandFormatter -> connected owner's SM_MESSAGE

//companion goal choose
  -> verify exact owner/companion, flags and exact offered quest in allowlist
  -> re-read candidate and current read-only eligibility
  -> compare stable semantic fingerprint (not distance/reference/presentation)
  -> store immutable chosen plan in the same runtime session
```

Компоненты:

- `QuestGoalCandidateProvider` читает только allowlist, `QUEST_DATA`,
  `XML_QUESTS`, NPC/item templates и static spawns. Он принимает лишь
  `MonsterHuntData` строгой формы и создаёт immutable candidate.
- `ReadOnlyQuestEligibility` является единственным местом чтения mutable
  owner domains и вызывает безопасный overload штатной quest eligibility.
- `QuestGoalPlanner` ничего не знает о `Player`, packets или companion; вход —
  snapshot/candidates/results, выход — один `QuestGoalPlan` или reason.
- `CompanionGoalSession` хранит `offered` и `chosen`; оба очищаются при
  dismiss, owner logout/disconnect/map-instance removal, runtime-disable и
  shutdown. Persistence hooks отсутствуют.
- `QuestGoalPlan` и `QuestGoalStep` — минимальные immutable DTO. Step kinds
  первого 2B: `TALK_TO_START_NPC`, `KILL_NPC_SET`, `REPORT_TO_END_NPC`.
- `QuestGoalCommandFormatter` формирует admin output и client-localized links;
  неизвестное поле печатает явно, а не придумывает.

`PlayerActionGateway` не расширяется: 2B ничего не исполняет. Goal session не
тикaет и не регистрируется отдельной задачей в scheduler.

## 6. Fail-closed candidate contract

Кандидат допускается только если одновременно:

1. ID находится в непустом allowlist (1–3 IDs) и template существует.
2. `QuestEngine.isHaveHandler(id)` true, а `XMLQuests.getQuest(id)` имеет exact
   type `MonsterHuntData`, не Java handler и не generic `XmlQuestData`.
3. Category — обычная `QUEST` или вручную подтверждённая `IMPORTANT`; template
   не `restricted`, не `EVENT`, `CHALLENGE_TASK`, `FACTION`, `TASK`,
   `NON_COUNT`, mission/test и не имеет `minlevel=99`.
4. `maxRepeatCount == 1`, `repeatCycle == null`, нет faction/mentor/timer,
   invasion/start-zone/start-distance/aggro auto-start semantics.
5. Есть ровно один логический start NPC set; end NPC явно задан либо безопасно
   наследуется от start; все IDs положительные.
6. Есть хотя бы один `quest_kill`; каждый objective имеет step 0, положительный
   count и непустые NPC IDs. Нет collect/inventory/work items/quest drops,
   data-driven или иных objectives.
7. Все prerequisites структурно описаны и поддержаны; unknown condition
   отклоняет candidate.
8. Start/end/target NPC templates существуют, их static spawns найдены на
   текущей non-instance map owner. Отсутствие или неоднозначность, которую
   нельзя стабильно разрешить, означает reject с reason.
9. Full read-only eligibility текущего owner успешна.

В репозитории нет канонического `gm/test` boolean. Поэтому их исключают
default-empty allowlist, category/restricted/minlevel filters и fixture-test
каждого разрешённого ID. Raw `name` с `[Test]`/`[Event]` служит дополнительным
fail-closed deny, но не основной эвристикой.

Static spawn не доказывает, что NPC достижим через двери/телепорты. Первый 2B
не строит путь: он ограничивает кандидата текущей открытой картой, показывает
координату как reference и отмечает ограничение в плане.

## 7. Алгоритм выбора и объяснение

1. Проверить flags, active companion и exact real connected/spawned owner.
2. Нормализовать allowlist: parse, reject duplicates/invalid count, sort by ID.
3. Provider fail-closed преобразует каждый ID в candidate или stable rejection
   reason; ни один handler не исполняется.
4. Снять read-only owner snapshot и выполнить eligibility для каждого
   candidate.
5. Оставить только candidates текущей map с полными start/kill/end steps.
6. Сортировать по tuple без `Rnd`:
   - минимальный абсолютный diff `ownerLevel - minLevel` при неотрицательном
     значении;
   - минимальная дистанция до выбранного start spawn;
   - меньшее суммарное kill count;
   - меньший quest ID как окончательный tie-break.
7. Сформировать reason: например, «доступен по штатным ограничениям; стартовый
   NPC на текущей карте; ближайший по level/distance; tie-break questId».
8. Сохранить план как `offered`, не как `chosen`; повторный `today` при том же
   snapshot выдаёт тот же результат.
9. `goal choose` заново строит/валидирует exact offered candidate и сравнивает
   стабильный semantic fingerprint. Изменение quest ID, minimum level,
   prerequisites, ordered objective kinds, target NPC IDs или counts делает
   offer stale. Изменение distance, reason/formatter text или nearest reference
   spawn само по себе stale не создаёт; актуальный rebuilt immutable plan
   записывается в runtime session.
10. Если кандидатов нет, вывести агрегированный стабильный reason: allowlist
    empty, wrong type, unsupported shape, ineligible reason, wrong map/missing
    spawn или stale proposal.

План содержит quest ID и localized link/name, reason, start NPC и map/coords,
finished prerequisites, последовательность steps, objective type, target
NPC IDs/names/counts, end NPC, основные reward groups и список unknown/limits.
Description выводится как `недоступно в server data`, если нельзя показать
клиентскую quest link как замену.

## 8. Command UX

Текущий [`ChatProcessor.getParamsFromString()`](../../game-server/src/com/aionemu/gameserver/utils/chathandlers/ChatProcessor.java#L99)
уже разбивает `goal choose` на два параметра. Изменять parser не нужно;
[`Companion.execute()`](../../game-server/data/handlers/admincommands/Companion.java#L21)
надо расширить для одного или двух параметров.

Рекомендуемый UX оставить предложенным:

```text
//companion today        # предложить, но не выбрать и не принять quest
//companion goal choose  # выбрать последнее предложение как runtime AI goal
//companion goal status  # показать offered/chosen и lifecycle binding
//companion goal clear   # удалить только runtime goal
```

Все успешные ответы `choose` содержат точную фразу `runtime AI goal only;
quest not accepted`.

## 9. Feature flags

Предлагаемые значения, оба безопасны по умолчанию:

```properties
ai.companions.quest_goals.enabled = false
ai.companions.quest_goals.allowed_quest_ids =
```

Условия включения: `ai.enabled && ai.companions.enabled &&
ai.companions.quest_goals.enabled`. Empty/invalid allowlist не мешает startup,
но `today` возвращает понятную ошибку и ничего не выбирает. Максимум три
уникальных положительных ID; первоначально рекомендуется один проверенный ID.

## 10. Предполагаемые изменяемые файлы реализации

Точный минимальный список для отдельного, явно подтверждённого implementation
turn:

Изменяемые:

- `game-server/src/com/aionemu/gameserver/configs/main/AIConfig.java`;
- `game-server/config/main/ai.properties`;
- `game-server/data/handlers/admincommands/Companion.java`;
- `game-server/src/com/aionemu/gameserver/services/ai/CompanionService.java`;
- `game-server/src/com/aionemu/gameserver/services/QuestService.java`;
- `game-server/src/com/aionemu/gameserver/model/templates/quest/XMLStartCondition.java`;
- `game-server/src/com/aionemu/gameserver/questEngine/handlers/models/MonsterHuntData.java`.

Новые в `game-server/src/com/aionemu/gameserver/services/ai/quest/`:

- `QuestGoalCandidateProvider.java`;
- `ReadOnlyQuestEligibility.java`;
- `QuestGoalPlanner.java`;
- `CompanionGoalSession.java`;
- `QuestGoalPlan.java`;
- `QuestGoalStep.java`;
- `QuestGoalCommandFormatter.java`.

Новые тесты в `game-server/test/com/aionemu/gameserver/services/ai/quest/`:

- `ReadOnlyQuestEligibilityTest.java`;
- `QuestGoalCandidateProviderTest.java`;
- `QuestGoalPlannerTest.java`;
- `CompanionGoalSessionTest.java`;
- `QuestGoalCommandFormatterTest.java`;
- `CompanionStage2BSourceBoundaryTest.java`.

`quest_data.xml`, `quest_script_data`, Java quest handlers, SQL/schema и DAO не
изменяются. `commands.properties` не меняется, потому что используется
существующий access level команды `companion`.

## 11. Реализация маленькими проверяемыми шагами

1. Добавить flags/allowlist parser и тесты default-off/empty/max-three.
2. Добавить безопасные read-only metadata getters/immutable copies для
   `MonsterHuntData` и start-condition descriptor; проверить отсутствие
   изменения startup registration.
3. Добавить тихий полный eligibility entry point с equipment и quest capacity;
   старые overloads оставить семантически неизменными.
4. Реализовать provider и fail-closed validation на одном fixture quest ID.
5. Реализовать pure planner и deterministic scoring/reasons.
6. Реализовать runtime `CompanionGoalSession`, stale revalidation и cleanup
   вместе с lifecycle 2A.
7. Добавить formatter и четыре command branches без изменения parser.
8. Добавить source-boundary tests, полный reactor и только затем ручной
   acceptance.

После каждого шага запускаются узкие tests; после последнего — полный Maven
reactor тем же Docker/JDK 25 процессом.

## 12. Автоматические тесты

Обязательные проверки:

- eligibility: missing/completed/active quest, race/class/gender/level/rank,
  completed and unmet prerequisites, required title/equipment/inventory,
  quest-log capacity и membership exception;
- `warn=false` read-only path всё равно проверяет required equipment;
- snapshot `QuestStateList`, every `QuestState.persistentState/vars/status`,
  inventory counts/kinah, XP и packet count совпадает до/после;
- DAO, `QuestService.startQuest/finishQuest/addOrUpdateQuest`, dialog handlers,
  ItemService и reward paths не вызываются;
- allowlist empty/duplicate/over-three/unknown ID; Java handler, generic XML,
  event/restricted/repeat/daily/faction/test/collect/data-driven/instance и
  malformed kill candidate отклоняются;
- missing/ambiguous wrong-map spawn даёт reason, не partial plan;
- supported `monster_hunt` извлекает start/end, ordered kill steps, IDs/counts,
  prerequisites и rewards без изменения template lists;
- одинаковые candidates/snapshot дают одинаковый plan; tie-break по quest ID;
- `today` создаёт только offered, `choose` — только runtime chosen; stale
  proposal отклоняется; status/clear idempotent;
- immediate choose, repeated `today`, value-equivalent plans, owner movement,
  changed distance/reason/formatter text и другая nearest reference spawn не
  дают ложный stale; chosen хранит актуальный rebuilt plan;
- реальное изменение stable semantics и новая active/completed quest state
  дают структурированный `STALE_OFFER`, очищают session и не выбрасывают
  exception из command formatter; no-offer/mismatch также структурированы;
- dismiss, logout/disconnect, map-instance change, feature disable и shutdown
  очищают session;
- formatter содержит quest ID/link, localized markers, coordinates, steps,
  rewards/unknowns и явную фразу «quest not accepted»;
- source-boundary запрещает imports/calls packet handlers, QuestEngine dialog,
  quest mutations, item/reward/DAO и persistence;
- все существующие stage-1/2A tests остаются зелёными.

## 13. Ручная приёмка — выполнена

1. Выбрать один заранее проверенный non-repeatable XML `monster_hunt` под race,
   level и текущую открытую map owner; зафиксировать его ID и ожидаемые XML
   start/end/kill/reward данные.
2. Зафиксировать для owner: строки `player_quests`, inventory/kinah, XP/level и
   active quest list. Зафиксировать template companion DB baseline этапа 2A.
3. Оставить goal flag false: `//companion today` должен отказать без изменений.
4. Включить goal flag и allowlist из одного ID, summon companion владельцем A;
   клиент B наблюдает, что presentation/follow 2A не изменились.
5. Выполнить `//companion today`; сверить ID, русское client-localized name,
   reason, start NPC/map/coords, prerequisite, kill target IDs/names/count,
   end NPC, rewards и unknown limitations с data files.
6. Повторить `today` без изменения owner: результат идентичен.
7. Выполнить `//companion goal status`: offered присутствует, chosen нет,
   quest journal owner и companion не изменены.
8. Выполнить `//companion goal choose`, затем status: chosen совпадает с
   revalidated offered и помечен runtime-only; в клиентском quest journal новый
   quest отсутствует.
9. Выполнить clear дважды: chosen/offered отсутствуют, companion продолжает
   штатный follow.
10. Создать ineligible condition (например, завершить prerequisite не выполнен,
    wrong level/race либо активировать allowlisted quest штатным клиентом):
    `today` возвращает точный reason и ничего не выбирает.
11. Проверить stale case: получить offer, штатно изменить eligibility владельца,
    затем `goal choose`; выбор отклонён.
12. Повторить с missing/unsupported ID: fail-closed reason, без exception и
    partial session.
13. Проверить dismiss, owner map change, logout и runtime-disable: goal session
    исчезает вместе с lifecycle; после нового summon старой цели нет.
14. Сравнить DB/runtime baseline: `player_quests`, inventory, XP, companion
    template/account неизменны. Убедиться, что packets принятия/dialog quest не
    появились и обычный ручной quest flow продолжает работать.

## 14. Риски и mitigation

- **Неполная eligibility.** Текущий `warn=false` пропускает equipment, а
  capacity живёт в `startQuest`. Mitigation: новый тестируемый read-only entry
  point, не копирование dialog flow.
- **Скрытая Java-логика.** Template может выглядеть простым, handler — нет.
  Mitigation: exact `MonsterHuntData` allowlist и fixture validation.
- **Mutable static templates.** JAXB getters возвращают live lists, а
  `QuestKill.getNpcIds()` нормализует их. Mitigation: immutable copy после
  startup и tests повторного чтения.
- **Ложная map-доступность.** Static spawn не означает достижимый путь.
  Mitigation: current non-instance map only, curated ID, reference coordinate и
  явное ограничение; 2B не двигается.
- **Локализация.** Server не хранит русский description. Mitigation: client
  quest/NPC/item links и честный `unknown`; не добавлять самодельный перевод.
- **Stale selection.** State меняется между today/choose. Mitigation: повторная
  eligibility и rebuild перед выбором.
- **Случайная persistence.** Connected owner штатно периодически сохраняется.
  Mitigation: planner не меняет persistable state; before/after snapshots и DAO
  source-boundary. Нельзя утверждать, что в процессе нет unrelated periodic DB
  save, только что planner не создаёт изменяемых данных.
- **Session leak.** Mitigation: lifecycle-bound cleanup и idempotence tests.

## 15. Rollback

1. Установить `ai.companions.quest_goals.enabled=false`; очистить runtime goal
   session, не удаляя companion 2A.
2. Проверить `goal status`/companion status и неизменность journal/inventory/XP.
3. Удалить command branches, package `services.ai.quest`, два config keys и
   только 2B overload/getters; не откатывать принятый 2A.
4. SQL/data rollback не нужен.
5. Запустить полный reactor и ручной regression сценарий 2A плюс штатное
   принятие одного квеста живым клиентом.

## 16. Подтверждённые решения реализации

Рекомендация: реализовать не автоматический поиск, а ещё более узкий первый
spike варианта B — **один configured quest ID** внутри инфраструктуры allowlist
с максимум тремя IDs. После приёмки одного Elyos либо Asmodian `monster_hunt`
можно отдельным решением добавить второй/третий ID. Это сохраняет целевой UX,
но резко снижает вероятность ложной доступности.

Владелец проекта подтвердил:

1. ADR-0002 и запрет automatic discovery в первом 2B.
2. Первый и единственный эксплуатационный quest ID — `1102` для Elyos/Poeta;
   template, prerequisites, XML handler, NPC templates, static spawns и
   non-instance map проверены до изменения Java-кода.
3. Допустимость небольшого безопасного overload/refactor в `QuestService` и
   `XMLStartCondition` без изменения поведения существующих overloads.
4. UX `goal choose` с обязательной точной runtime-only подписью.
5. При нескольких static spawns выбирается ближайший к owner по squared
   distance с детерминированным tie-break; координата помечается `reference`.
6. Client-localized quest/NPC/item links достаточны; отсутствующее server-side
   русское description выводится как недоступное, без выдуманного текста.
