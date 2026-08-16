# AI MVP: текущее состояние

Дата: 2026-08-16

Это короткая точка входа в эксперимент. Главный продуктовый ориентир —
[`PRODUCT_VISION_AND_ROADMAP.md`](../../PRODUCT_VISION_AND_ROADMAP.md). Технические границы и
порядок этапов задаёт [`AION_AI_MVP_SPEC.md`](../../AION_AI_MVP_SPEC.md); детальные планы и ADR нужно
читать только когда задача затрагивает соответствующую границу.

## Принятая база

| Slice | Результат | ADR | Commit |
|---|---|---|---|
| 0/1 | `Player` без `AionConnection`, видимость, movement, cleanup | [ADR-0001](ADR-0001-SERVER-CONTROLLED-PLAYER.md) | `cf3656ba8` |
| 2A | один PERSONAL_COMPANION, summon/dismiss, follow/stay | ADR-0001 | `5747dbff6` + `cf3656ba8` |
| 2B | read-only `today`, offer/choose/status/clear для exact quest 1102 | [ADR-0002](ADR-0002-READ-ONLY-QUEST-GOAL-PLANNING.md) | `6f354a9a9` |
| 2C | owner-triggered checked basic hit, physical companion, reward attribution owner | [ADR-0003](ADR-0003-SERVER-CONTROLLED-COMBAT-ACTIONS.md) | `ba6320ce1` |
| 2D | native 1102 tracking, quest-scoped combat, 3/3/turn-in/COMPLETE | [ADR-0004](ADR-0004-COMPANION-QUEST-EXECUTION-TRACKING.md) | `c3fad073c` |
| 2E | persistent owner→companion binding и chosen goal intent; restore WAITING/1/3/COMPLETE | [ADR-0005](ADR-0005-COMPANION-GOAL-PERSISTENCE.md) | этот commit |

Все пять ADR и этапы 1/2A/2B/2C/2D/2E приняты владельцем после ручных
проверок в реальном клиенте. Финальная автопроверка 2E:
157 tests, failures/errors/skips `0/0/0`.

## Неизменные инварианты

- AI body — fully loaded ordinary `Player`; fake connection и client packet simulation запрещены.
- Все действия идут через checked domain gateways; direct HP/quest/reward mutation запрещена.
- Общий budgeted scheduler; никаких thread/future на AI.
- Настоящие journal, inventory, equipment, skills и currency остаются единственным
  источником истины.
- Все AI flags default-off; allowlists узкие; любое расхождение отклоняется fail-closed.
- Runtime-копия template Cool не сохраняется; account Test остаётся deactivated.

## Последний принятый шаг

2E — persistence owner→companion и выбранной `COMPLETE_QUEST` intent с восстановлением
после summon/restart. Реализация, схема и ручная restart/re-summon проверка
WAITING → native 1/3 → COMPLETE приняты.
Решение и scope: [ADR-0005](ADR-0005-COMPANION-GOAL-PERSISTENCE.md) и
[IMPLEMENTATION_PLAN_2E](IMPLEMENTATION_PLAN_2E.md).
После ручной приёмки full reactor повторён: 157 tests, failures/errors/skips `0/0/0`.

Следующий slice этапа 2 нужно выбрать из product create/ownership lifecycle,
real group integration или минимального skill priority; затем нужна финальная
сквозная приёмка. Этап 3 ещё не начат.

## Наблюдения для следующих slices

- Follow может кратко блокироваться геодатой; в ручной 2E-проверке companion
  продолжил движение после перемещения owner.
- Combat status сворачивает конкретный quest authorization reason в общий
  `TARGET_NOT_ALLOWED`; отдельный spawn template `210133` был безопасно отклонён
  как отсутствующий в immutable manifest.
