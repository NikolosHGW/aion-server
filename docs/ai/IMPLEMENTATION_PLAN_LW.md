# План эволюции MVP → Living World + Autonomous Society

**Статус:** Proposed. Рабочие C3–C8 не отменяются, текущий следующий шаг — C3. Статус этапа только после кода/тестов/игровой проверки.

## Стратегия

Строить по вертикальным срезам с проверяемым человеческим опытом, а не заранее весь универсальный фреймворк. Использовать существующие `ServerControlledPlayer`, scheduler и native services с учётом их реальных ограничений. C2 даёт runtime receipts и отдельный BodyCheckpoint, но не durable world event/outbox и не атомарную экономику. Явно разделять body state, verified world event, subjective agent memory и political contract. Приоритет game-server stability выше AI.

| Шаг | Предусловия | Что добавить | Приёмка |
|---|---|---|---|
| **D0: design** | актуальный HEAD и снимок документации | Интегрировать утверждённое продуктовое видение, проверить предпосылки, подготовить schema/flag plan | ADR-0007…0009 остаются Proposed; их принятие — отдельное решение после проектирования и проверок |
| **C3–C6** | существующий C2 | Синхронизация уровня/роли, native skills/healing, item transfer, navigation; сохранить test/status discipline | Проверенная полезность компаньона, без дублей и fake connection |
| **W0: наблюдаемые события** | C2 + закрытые узкие audits + отдельное задание | Один persisted native quest event, stable source key, личная проекция и read-only status | Replay/restart не создаёт дубля или native награды; права пары доказаны |
| **C7; optional P1: компаньон-история** | Для C7 — прежние зависимости; для P1 — W0 и базовое выполнение задач | P1 добавляет `SharedEpisode`, `OwnerGoalIntent`, одно предложение и отказ/смену цели | Исходный C7 не зависит от полной памяти или Living World; P1 проверяется отдельно |
| **C8** | C3–C7 | Общая клиентская матрица, perf и restart | Принято в игре, не только в unit tests |
| **A1–A2** | проверенное нативное гражданское reward/quest flow | Один independent citizen, native XP/items, goal/action pipeline | Полный реальный цикл без владельца |
| **W1: живой регион** | W0, A1/A2, отдельно спроектированные bounded transitions и новый content gateway | Одна state machine региона; две поддерживаемые стратегии участия | Мир меняется при 0 людях с ограниченным catch-up; ошибки fail-closed, нет offline XP/items/kinah и fake native siege capture |
| **A3 + S1** | W1, citizen lifecycle | 3–5 граждан с разными целями, знаниями, отказами и relationships | Разные планы при одинаковом событии, нет global omniscience |
| **A4/A5 + S2** | group and economy gateways | Кооперация, формирование одного AI-легиона, real resource flow | Договорённость исполняется без дублирования ресурсов |
| **S3: политика** | S2, region state, native faction audit | Два легиона, конкурирующие цели, договоры, маленькая застава | Player может помочь любой стороне допустимыми действиями |
| **S4: осады** | PvP/legion/fortress audit и нагрузка | AI на оригинальных осадах + стратегические preparations | Нет owner change вне native siege flow, fairness verified |
| **S5: межфракционная политика** | S4 и client/server matrix | Cross-faction treaties, позже mixed legions если осуществимо | Нет несоответствий hostility/party/UI/packet и exploit обходов |
| **Optional LLM** | есть memory/policy/action gateway | Диалог и soft long-term suggestions | Полное отключение не останавливает мир; no direct rewards |

Не требовать C8 до начала анализа W0: документацию и read-only аудит можно выполнять параллельно. Но кодовая интеграция обязательна с узкой зависимостью и отдельными флагами.

Это карта зависимостей расширений, а не замена очереди C3–C8/A1–A7 из
[DELIVERY_PLAN](DELIVERY_PLAN.md). Текущий следующий этап — C3. Данный проход
только документальный; W0 ещё не заказан к реализации. Новые ADR остаются
Proposed, кодовый аудит сам по себе их не утверждает. Клиентская приёмка C1/C2
отложена владельцем до общего прохода и не объявляется выполненной.

## Первый осуществимый world slice (W0)

**Цель:** доказать «игра может помнить поступок, не вмешиваясь в native quest/progression».

Конкретный кандидат — сохранённое native завершение квеста 1102 владельцем
при подтверждённом присутствии его компаньона на сдаче. `QuestEngine.onQuestCompleted`
сам по себе не является DB commit receipt. Нужны новый узкий read-only evidence
gateway и отдельная атомарная запись event + personal projection; сейчас их нет.
Событие не доказывает участие во всех убийствах или атомарность всех наград.

Последовательность: captured witness candidate → проверка committed native
quest row → идемпотентная запись личной памяти → read-only status → restart →
тот же status. Подробные файловые границы, crash semantics, флаги и тесты:
[IMPLEMENTATION_PLAN_W0](IMPLEMENTATION_PLAN_W0.md). Все имена новых компонентов
и флагов в этом плане — предложения, а не уже существующие API.

**Не делать в W0:** автономные легионы, захват деревни, off-screen item progression, client UI, абстрактный «универсальный event bus» для всех систем Aion.

## Следующий видимый игровой срез (W1)

**Деревня и караван.** Будущий сценарий одного региона: караван/страж/угроза,
2–3 состояния и два реальных способа участия. До его реализации отдельно
спроектировать [ADR-0008](ADR-0008-WORLD-STATE-SIMULATION.md): bounded transitions,
clock/catch-up, versions, replay, fail-closed и conflict tests. Первым кандидатом
может быть только CALM → THREAT без спавна или наград; караван и защита требуют
ещё не созданного content/action gateway. При нуле людей допустимо будущее
стратегическое изменение, но не offline XP/items/kinah или поддельный siege
capture. Реальные AI в World выполняют native действия и без наблюдателей.

## Организационный срез (S2/S3)

1 AI-гладиатор с целью защитить территорию + 2 участника, один живой игрок; организация собирает resources и договаривается об активности. Другая организация действует против неё. Игрок способен предложить самостоятельную тактику через разрешённые действия; outcome не предрешён. Пока API межфракционной дружбы не проверено, обе организации остаются в совместимой native фракции. Native осады не менять до S4.

## Наблюдаемость и тесты для каждого slice

- Unit/property tests: deterministic scoring, event dedupe, projection replay, distinct relationship/belief vs fact, privilege/visibility, idempotent agreement.
- Integration: real Aion native action, MariaDB transaction/rollback/restart, two owners and two AI roles, no duplicate inventory/XP/kinah, stale action ID rejected.
- Gameplay: two real clients, late observer, movement/animation/presentation, no fake AI disclosure, native PvP/party compatibility.
- Performance: baseline 0/10/20 agents, p95/p99 tick, queue depths, native packet load, region materialization conflicts, failure after restart, backpressure and flags.
- Product: player understood next activity within 5–10 min, meaningful 20–40 minute session, can leave for week and return without punishment, at least two truly different player strategies for same region problem, NPC remembers only what it could have known.

## Stop conditions

- Новый домен мутирует Native QuestState/HP/XP/items напрямую или не выдерживает duplicate/restart.
- AI-action bypasses native restrictions or faction checks.
- World strategic change overwrites live player encounter, creates resources by fiat or silently transfers native fortress.
- LLM/planner on game tick; game-server performance degrades above budget.
- Agent continues to spam, monopolize content, force decisions, or reward AFK/24x7 advantage.

## Integration review checklist

Перед Java/SQL: проверить actual HEAD against snapshot; native hook / transaction commit, actor identity, access policy, lifecycle, named classes, migrations, player/world presence, loot attribution, siege/faction. Выдать updated ADR status и точные file-level diff. Предложения выше — не утверждение о существовании соответствующих методов.

Результат документального прохода и незакрытые предпосылки:
[LIVING_WORLD_INTEGRATION_REVIEW_2026-11-06](LIVING_WORLD_INTEGRATION_REVIEW_2026-11-06.md).
