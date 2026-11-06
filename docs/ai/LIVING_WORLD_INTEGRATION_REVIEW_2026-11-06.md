# Living World V2: документальная интеграция и аудит предпосылок

Дата: 2026-11-06. Это отчёт по документации и чтению кода, **не validation новой
функциональности**. W0 не реализован. Следующий текущий этап — **C3**.

## 1. Основание и защита текущей работы

- Репозиторий: `C:\dev\aion\latest\aion-server`.
- Ветка при проверке: `integration/ai-4.8`.
- HEAD до изменений: `56ef9b6cdae95ada801c5885ea8f62fecd507fd2` — точно совпал
  со снимком пакета; исходный working tree чистый. Новых изменений поверх
  снимка, требующих слияния, в этой проверке не оказалось.
- Пакет: `Aion-Living-World-V2.zip`, SHA-256
  `8940b96071d91c19f5c8e2cc1112313f121fa8d1815bc2994b01180b5e3ef219`.
  Все 11 Markdown-файлов overlay совпали с распакованной копией побайтово.
- `feature/ai-mvp` сохраняется на `1f6dfd9c250f9a4d0c196ef85117ce38eafb0aa3`.
  Совет README_FIRST создать ещё одну ветку не применён: действующее решение
  владельца — продолжать в существующей интеграционной ветке.
- Проверены README_FIRST, PATCH_MAP, CHANGES.diff, все overlay-документы,
  действующие ADR-0001…0006, PROJECT_STATE/DELIVERY_PLAN, C1/C2 планы и validation,
  native/AI код по перечисленным ниже границам.
- Проект — Maven reactor с `commons`, `game-server`, `chat-server`, `login-server`;
  основные спеки в корне и `docs/ai`. Java, SQL, config и клиент не изменялись.

Продуктовое направление утверждено: фэнтезийная MMORPG-песочница на основе
Beyond Aion 4.8, автономное общество, память последствий и личная история;
боёвка Aion, свобода игрока, короткие сессии и честная маркировка AI сохраняются.
Это не означает принятия технических ADR или наличия готового Living World Engine.

## 2. Что интегрировано и исправлено

| Документы | Результат |
|---|---|
| [PRODUCT_VISION_AND_ROADMAP](../../PRODUCT_VISION_AND_ROADMAP.md), [AION_AI_MVP_SPEC](../../AION_AI_MVP_SPEC.md) | Расширенное видение, companion story partner, самостоятельные жители/организации; уточнена граница native activity и будущего strategic state |
| [DELIVERY_PLAN](DELIVERY_PLAN.md), [PROJECT_STATE](PROJECT_STATE.md) | Новые точки входа, реальный статус C1/C2, C3 следующим, сохранены C3–C8/A1–A7 и решение отложить общую клиентскую приёмку |
| [LIVING_WORLD_ARCHITECTURE](LIVING_WORLD_ARCHITECTURE.md) | Разделены native truth, confirmed events, субъективная память, политические договоры; стабильный source key, локальный cursor, новые gateways как незакрытая предпосылка |
| [COMPANION_STORY_PARTNER_SPEC](COMPANION_STORY_PARTNER_SPEC.md), [AUTONOMOUS_SOCIETY_SPEC](AUTONOMOUS_SOCIETY_SPEC.md) | Продуктовые сценарии и границы знания/самостоятельности; будущие возможности не названы реализованными |
| [ADR-0007](ADR-0007-WORLD-EVENTS-AND-MEMORY.md), [ADR-0008](ADR-0008-WORLD-STATE-SIMULATION.md), [ADR-0009](ADR-0009-AI-SOCIETY-AND-POLITICS.md) | Добавлены как **Proposed**. Доказательство факта, bounded simulation и native social/siege ограничения требуют отдельного проектирования |
| [IMPLEMENTATION_PLAN_LW](IMPLEMENTATION_PLAN_LW.md) | Карта расширений; D0 не утверждает ADR, P1 не блокирует исходный C7, единая нумерация S-этапов |
| [IMPLEMENTATION_PLAN_W0](IMPLEMENTATION_PLAN_W0.md), этот отчёт | Отдельная маленькая задача с файловыми границами, audits, crash/rollback semantics и тестовой матрицей |

Всего: четыре обновлённых документа, семь новых из overlay и два новых документа
по результатам аудита. Accepted ADR, исторические implementation plans,
[ARCHITECTURE](ARCHITECTURE.md) и журналы приёмки сохранены без переписывания.
Исторические запреты описывают принятую базу; будущее расширение явно оформлено
отдельными Proposed-документами, а не задним числом объявлено частью 2A–2G/C1/C2.

### Коллизии имён и ссылок

На проверенном HEAD номера 0007, 0008, 0009 свободны. Фактические новые имена:

- `ADR-0007-WORLD-EVENTS-AND-MEMORY.md`;
- `ADR-0008-WORLD-STATE-SIMULATION.md`;
- `ADR-0009-AI-SOCIETY-AND-POLITICS.md`.

В PATCH_MAP упомянут диапазон W0–W5, но в overlay нет самостоятельных планов
W2–W5: есть W0/W1/P1/S1–S5. Несуществующие этапы/файлы не создавались ради ссылки.
Внутри society spec исходные S1–S6 не совпадали с IMPLEMENTATION_PLAN_LW;
принята нумерация последнего: A1/A2 — один citizen, S1 — 3–5, S2 — легион,
S3 — политика, S4 — native осады, S5 — cross-faction. CP-M0 соотнесён с W0,
CP-M1/2 — с P1, остальные CP-M остаются последующими расширениями.

При последующем обновлении upstream вновь проверять номера: отсутствие коллизии
на этом HEAD не резервирует их в других ветках. Новые ссылки ведут на фактические
имена; ссылок на отсутствующий репозиторный START_HERE не добавлено.

## 3. Что подтвердилось чтением кода

| Граница | Проверенные факты | Следствие |
|---|---|---|
| Native completion | [QuestService.finishQuest](../../game-server/src/com/aionemu/gameserver/services/QuestService.java#L77) выдаёт native результат и устанавливает COMPLETE; [QuestEngine.onQuestCompleted](../../game-server/src/com/aionemu/gameserver/questEngine/QuestEngine.java#L256) вызывает зарегистрированные quest handlers | Есть точка для будущего узкого наблюдения, но это in-memory callback, не durable event bus |
| Сохранение квеста | [QuestState](../../game-server/src/com/aionemu/gameserver/questEngine/model/QuestState.java#L68) отмечает состояние для сохранения; [PlayerQuestListDAO.store](../../game-server/src/com/aionemu/gameserver/dao/PlayerQuestListDAO.java#L60) делает отдельные insert/update/delete commits и ловит SQL-ошибки | Возврат store не доказывает commit. Нужен точный exception-aware read-only evidence gateway |
| Граница всей записи | [PlayerService.storePlayer](../../game-server/src/com/aionemu/gameserver/services/player/PlayerService.java#L84) вызывает отдельные DAO для разных доменов | Quest COMPLETE не равен атомарному сохранению всех наград, тела и нового события |
| Наличие actor | [Player.isOnline](../../game-server/src/com/aionemu/gameserver/model/gameobjects/player/Player.java#L448) проверяет клиентское соединение | AI может находиться в World и иметь isOnline=false. Ноль людей не означает право симулировать offline farm |
| Quest hooks AI | [PlayerController](../../game-server/src/com/aionemu/gameserver/controllers/PlayerController.java#L98) возвращается раньше для server-controlled в see/notSee/target/zone paths | Нельзя считать native quest hooks граждан уже полностью доступными; требуется A1 role-specific audit |
| Attribution | [PersonalCompanionAttributionRegistry](../../game-server/src/com/aionemu/gameserver/services/ai/combat/PersonalCompanionAttributionRegistry.java#L21) ограничен COMPANION; [DamageList](../../game-server/src/com/aionemu/gameserver/controllers/attack/DamageList.java#L23) использует attribution; [NpcController](../../game-server/src/com/aionemu/gameserver/controllers/NpcController.java#L201) выдаёт native quest/XP/drop результаты | Принятый вклад компаньона относится к владельцу. Второй share не вводить; независимая citizen прогрессия ещё требует отдельного пути |
| C2 | [CompanionBodyDAO](../../game-server/src/com/aionemu/gameserver/dao/CompanionBodyDAO.java#L159) сохраняет проверенный body checkpoint; [CompanionService](../../game-server/src/com/aionemu/gameserver/services/ai/CompanionService.java#L606) имеет status и runtime lifecycle | Можно использовать существующий owner/binding фундамент, но C2 не является event outbox или историей witness presence |
| Shared account | [AccountService](../../game-server/src/com/aionemu/gameserver/services/AccountService.java#L85) отдельно загружает server-controlled account/body и ограничивает общие данные | Изоляция C2 полезна, но не даёт автоматически разрешения на warehouse/mail/broker/treasury |
| Обмен | [ExchangeService](../../game-server/src/com/aionemu/gameserver/services/ExchangeService.java#L220) проверяет isOnline; performTrade отдельно сохраняет два inventory. [InventoryDAO](../../game-server/src/com/aionemu/gameserver/dao/InventoryDAO.java#L218) имеет собственные операции/commits | Native service существует; атомарная durable AI-сделка с повтором/сбоем пока не доказана |
| Item operations | [PlayerRestrictions](../../game-server/src/com/aionemu/gameserver/restrictions/PlayerRestrictions.java#L277) использует isOnline в canUseItem | C4/C5 нужны конкретные checked адаптеры; fake connection не решение |
| Groups/legions | [LegionService](../../game-server/src/com/aionemu/gameserver/services/LegionService.java#L834) проверяет online и race; [PlayerGroupService](../../game-server/src/com/aionemu/gameserver/model/team/group/PlayerGroupService.java#L216), [PlayerAllianceService](../../game-server/src/com/aionemu/gameserver/model/team/alliance/PlayerAllianceService.java#L273) удаляют offline members | Native invitations/consent/lifecycle нельзя просто вызвать как готовый citizen API |
| Faction | [GroupConfig](../../game-server/src/com/aionemu/gameserver/configs/main/GroupConfig.java#L22), [LegionConfig](../../game-server/src/com/aionemu/gameserver/configs/main/LegionConfig.java#L231) имеют default-false cross-faction invite flags; [Player](../../game-server/src/com/aionemu/gameserver/model/gameobjects/player/Player.java#L914) сохраняет native hostility | Частичная конфигурируемость есть; единая дипломатическая модель и mixed-faction совместимость не подтверждены |
| PvP | [PvpService](../../game-server/src/com/aionemu/gameserver/services/PvpService.java#L196) фильтрует offline участников; при LOG_PL далее обращается к client IP/MAC | Роль без соединения требует проверки rewards/logging/teams; нельзя объявлять готовность боевого AI для siege |
| Осада | [FortressSiege.onSiegeFinish](../../game-server/src/com/aionemu/gameserver/services/siege/FortressSiege.java#L130) выбирает capture/defend и сохраняет location; [SiegeDAO](../../game-server/src/com/aionemu/gameserver/dao/SiegeDAO.java#L58) возвращает success boolean | Native outcome и persistence — отдельные границы; готового durable siege event receipt не выявлено |
| Иные смены owner | [SiegeService](../../game-server/src/com/aionemu/gameserver/services/SiegeService.java#L245) содержит прямые native control/reset пути | Неверно описывать весь текущий сервер как «owner меняется только после боя». Запрет касается будущего politics planner: ему эти пути не предоставлять как fake capture gateway |

Дополнительная lifecycle-граница: [ShutdownHook](../../game-server/src/com/aionemu/gameserver/ShutdownHook.java#L80)
останавливает компаньонов до отключения/сохранения human players; normal logout
тоже отдельно очищает companion и сохраняет владельца. W0 не может полагаться
на активное тело спутника в момент фактического native DB save. Witness context
должен происходить от точного наблюдения и сохраняться самостоятельно.

Принятый [ADR-0004](ADR-0004-COMPANION-QUEST-EXECUTION-TRACKING.md) ограничивает
tracker read-only snapshot/polling. Вставлять запись memory или DAO в этот tracker
под видом «существующего event hook» нельзя. [ADR-0005](ADR-0005-COMPANION-GOAL-PERSISTENCE.md)
хранит выбранное намерение, не зеркальный quest progress. [ADR-0006](ADR-0006-PRODUCT-COMPANION-CREATION.md)
не утверждает новый social/economic gateway.

## 4. Что не подтвердилось или требует отдельного доказательства

1. **Готовый generic durable event hook — отсутствует в проверенных границах.**
   Quest callback и action receipt недостаточны. Completion может произойти в
   памяти до сохранения, DAO ловит ошибки, единый commit с наградами не найден.
2. **C2 покрывает экономику и память событий — неверно.** Его checkpoint не
   гарантирует cross-player atomic trade, durable occurrence ID или witness history.
3. **Любой обычный Player автоматически поддержит все AI-действия — неверно.**
   Connection/online, role, приглашения и suppressions требуют конкретной адаптации.
4. **Межфракционные группы/легионы полностью запрещены — слишком сильное утверждение.**
   Invite flags есть. Но включение флага не меняет весь PvP, party, UI, quest eligibility,
   siege ownership и rewards; цель полной совместимости пока не доказана.
5. **Готовые AI society/economy/PvP/siege gateways — не найдены.** Их наличие нельзя
   вывести из имён native services. Такие адаптеры — незавершённые предпосылки будущих этапов.
6. **Материализация региона, durable world ledger, личная биография, политические
   организации и LLM уже работают — нет.** Они описаны как будущие компоненты.
   Совместимость нового поведения с клиентом и нагрузка в этом проходе не проверялись.
7. **Память любого завершённого квеста можно восстановить после restart — неверно.**
   Native row не хранит факт присутствия компаньона. Без ранее сохранённого witness
   нельзя создавать SharedEpisode задним числом.

## 5. Разрешение конфликта no background simulation

Исторический запрет остаётся верным для принятой реализации. Обновлённый договор:

- **NO offline XP/items/kinah**, phantom kills, backdated quest completion и fake
  native siege capture. Companion по-прежнему не получает второй reward share.
- Реальные AI, находящиеся в World, действуют через native mechanics даже при
  нуле человеческих наблюдателей. `isOnline` не определяет этот продуктовый режим.
- Strategic region-state progression — **отдельная будущая Proposed-система**.
  Только после явного проектирования: bounded transitions/catch-up, versions/CAS,
  clock, replay, ownership, test coverage, fail-closed. Counter угрозы не равен
  армии, предмету или валюте. Материализация требует нового проверенного content gateway.
- Никакой direct SQL/setter передачи native крепости планировщику. Новые небольшие
  outposts не являются лазейкой: самостоятельная модель ресурсов, разрешённых
  исходов и fairness ещё должна быть спроектирована. W0 не меняет регионы вообще.

## 6. Audits до W0 и до поздних расширений

**Перед W0:** точный источник нормальной сдачи1102; snapshot/witness/claim/lease;
committed query, source read/commit race и нормализация complete_time; stable occurrence identity и reuse
IDs; crash windows и pending lifecycle при logout/shutdown; отдельная memory
transaction/UNIQUE; privacy/status; bounded worker и rollback flags.
Для каждого задан stop condition в [W0, §2](IMPLEMENTATION_PLAN_W0.md#2-что-должен-закрыть-аудит-перед-первым-изменением-кода).

**Позже, не как блокер малого W0:**

| До этапа | Audit / отсутствующий gateway |
|---|---|
| C3/C4 | Class/level/skills, native restrictions и casting/healing без connection hacks |
| C5/A5 | Use/equip/loot, exact item ownership, NPC trade vs player exchange vs broker, shared account, crash/rollback и durable transaction receipts |
| A1/A2 | Role-specific native quest events, kill/loot credit и independent rewards; не копировать companion attribution гражданину |
| W1 | Разрешённые region transitions, deterministic clock/catch-up, materialization/version conflict, уже активные encounters; content gateway пока нет |
| A4/S2/S3 | Native group/legion create/invite/consent/leave/disband/treasury, offline cleanup, authority человеческого лидера; checked AI social gateways пока нет |
| S4 | Native siege start/kill/finish/defend/reset/DB failure, участие без socket, racecounter/rewards/owner/legion persistence; новый participation gateway и confirmed outcome receipt |
| S5 | Hostility/duel/party/alliance/legion/PvP/quests/trade/flight/packets/UI matrix, race vs political affiliation; flags отдельно от права на действие |

## 7. Первый реализационный slice и его blockers

Предлагается отдельная задача [W0](IMPLEMENTATION_PLAN_W0.md): **одно сохранённое
native завершение1102 → личный эпизод присутствия компаньона → read-only status
после restart**. Изменения будущей задачи ограничены source-specific observation,
новым read-only native evidence gateway, bounded reconciler, отдельным memory DAO,
status и двумя default-off flags. В плане перечислены конкретные существующие и
предлагаемые файлы, DDL/test границы и критерии приёмки. Здесь они не создавались.

Главный blocker сильной гарантии — нет атомарного native completion + durable
candidate/outbox. Узкий W0 допускает **явную потерю наблюдения до сохранения
candidate**, но никогда не выдумывает факт. Для нулевой потери потребуется
дополнительный native commit/outbox gateway и отдельное решение о границе задачи.
Если даже выбранный узкий native evidence/witness не доказывается, источник
отклоняется fail-closed; нельзя «починить» это SQL-записью quest/XP или fake socket.

## 8. Риски и тестовая матрица

| Риск | Обязательная проверка будущего кода |
|---|---|
| Память чужого владельца или ложное совместное участие | Два owner, общий AI account, stale generation, body reuse/rebind, отсутствие witness; фильтрация status |
| Дубли / расхождение с native | Callback повторён с новым UUID, concurrent workers, same-key payload conflict, native save failed, quest reset, time precision |
| Потеря/частичный commit | Crash до/после candidate, native save, event/projection, ack; два JVM запуска; rollback всех новых записей одной транзакции |
| Повторная награда | Сравнение native quest/XP/items/kinah до/после memory retry/replay/toggle; отсутствие native write calls |
| Зависание игры / неработающий rollback | Bounded queue/batch/backoff, SQL outage, overflow metrics, четыре комбинации flags, in-flight disable barrier, запуск с flags off без DDL |
| Продукт принят только на бумаге | После реализации — реальная сдача1102 с двумя владельцами и restart; отдельно общая клиентская C1/C2/C3… приёмка |
| Будущий мир расходится с Aion | Поздние region version/materialization tests, zero-observer native activity, no offline rewards, factions/legions/PvP/siege+client matrix |

Подробная W0-матрица: [план, §6](IMPLEMENTATION_PLAN_W0.md#6-тестовая-матрица-и-критерий-завершения).
Здесь не заявляется её прохождение. Предыдущие **271 тест C1** и **287 тестов C2**
остаются историческими результатами соответствующих validation logs, а не
результатом этого прохода. Java-сборка, SQL-пробы и игровые проверки повторно
не запускались: изменены только Markdown-документы.

## 9. Проверки документального результата

Выполнены проверка relative Markdown links, регистра путей, heading/line anchors,
новых ADR numbers/status и `git diff --check`. Проверено 35 Markdown-файлов и
348 относительных ссылок. Во всех изменяемых документах ошибки ссылок отсутствуют.
Коллизий ADR нет; все три новых ADR имеют Proposed.

Найдена одна **ранее существовавшая** неточность вне области изменений:
`docs/ai/IMPLEMENTATION_PLAN_2B.md` ссылается на
`QuestKill.java#L59`, а текущий файл короче. Исторический план оставлен без
исправления задним числом; при необходимости позже добавляется отдельная заметка.

Побайтовые SHA-256 всех **5 554** остальных tracked files совпали с исходным
снимком; защищены в том числе Accepted ADR, historical plans/validation и весь
Java/SQL/config. Историческая ветка сохранила исходный hash. В область изменения
входят ровно 13 Markdown-документов. Эти проверки не заменяют будущие runtime tests.
