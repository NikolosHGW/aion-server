# ADR-0008: strategic world-state simulation vs native personal progression

**Status: Proposed (не принято технически).**

Результаты проверки текущего кода: [аудит интеграции](LIVING_WORLD_INTEGRATION_REVIEW_2026-11-06.md).
Это проект будущего расширения; исторический ARCHITECTURE и Accepted ADR не меняются.

## Проблема

Исходные `AION_AI_MVP_SPEC.md §4.5`, `ARCHITECTURE.md §6` и `DELIVERY_PLAN.md`
запрещали фоновую симуляцию AI и offline XP/items. Актуальные master spec и
delivery plan уточнены этим Proposed-направлением; исторический ARCHITECTURE
не переписан. Новый живой мир должен менять некоторые обстоятельства даже без
подключённых людей. Запрет offline XP/items/kinah при этом остаётся.

## Предлагаемое решение

**Разделить два домена:**

1. `Native Actor Activity`: movement/combat/crafting/quest/loot/XP/currency/gear, real `Player` in World. Только в native мире, после проверенного результата. Offline citizen character получает **0 XP/0 items/0 kinah**.
2. `Strategic Region State`: будущая budgeted scheduled model с ограниченными
   переходами, таймерами и версиями. Первый кандидат — только `CALM → THREAT`.
   `ATTACK/OCCUPIED/RECOVERY` требуют отдельного контента и подтверждённых условий;
   это не состояния native fortress. Модель может работать без человеческих
   наблюдателей, но не начисляет offline XP/items/kinah и не меняет native ownership.

В первой итерации допустимо только накопление угрозы, сроки, известные последствия и materialization подходящего world event через проверенные spawn/action service. Изменения без игрового наблюдателя логируются и объясняются; при подходе игрока не подменяют уже запущенный encounter.

Крупные осады Aion — строго через существующий siege result. Не включать «виртуальный захват» и SQL-передачу крепости при отсутствии игроков. Возможность стратегического расчёта для новых малых застав рассматривается позже, после отдельного resource/force model, fairness audit и симметричных правил для игроков и NPC.

## Почему не просто offline farm

Это бесплатно создаёт ресурсы, удешевляет затраты на путешествие и даёт 24/7 AI преимущества. Цель — изменения обстоятельств, порождающие реальные занятия, а не незаслуженный персонажный прогресс.

## Safety

- `ai.region_sim.enabled=false` default.
- bounded, deterministic/seeded transitions; simulation clock и repeatable replay.
- тесты границ таймеров, долгого простоя, ограниченного catch-up и повторного restart;
  нельзя проигрывать месяцы неограниченным циклом на игровом тике.
- native activity остаётся native и при нуле наблюдателей; новая стратегическая
  модель не выдаёт личные ресурсы и не создаёт готовое войско из агрегатного счётчика.
- неопределённость authority, конфликт version или активный encounter останавливают
  переход fail-closed; нужны test coverage и доказательство неизменности native DB.
- offline absence не уничтожает личную memory и не вводит ежедневные penalties.
- при перегрузке world-strategy calculation деградирует прежде нативной игры.
- kill switch, region version-lock, rollback/reconciliation; метрики на каждый off-screen transition.

## Приёмка

Без игроков регион переходит из CALM в THREAT, событие подтверждается и после входа игрок видит реальную угрозу; ни одному offline AI не выдан предмет/XP; перезапуск не запускает угрозу дважды; крупная native крепость не переходит в другое владение от strategic simulation.
