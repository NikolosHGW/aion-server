# AI MVP: текущее состояние

Дата: 2026-09-11

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
| 2E | persistent owner→companion binding и chosen goal intent; restore WAITING/1/3/COMPLETE | [ADR-0005](ADR-0005-COMPANION-GOAL-PERSISTENCE.md) | `3dd9e5d15` |
| 2F | explicit create и immutable ownership постоянного PERSONAL_COMPANION вне slots owner | [ADR-0006](ADR-0006-PRODUCT-COMPANION-CREATION.md) | `03eb3b7d1` |

Все шесть ADR и этапы 1/2A/2B/2C/2D/2E/2F приняты владельцем после ручных
проверок в реальном клиенте. Финальная автопроверка 2F: 166 tests,
failures/errors/skips `0/0/0`.

## Неизменные инварианты

- AI body — fully loaded ordinary `Player`; fake connection и client packet simulation запрещены.
- Все действия идут через checked domain gateways; direct HP/quest/reward mutation запрещена.
- Общий budgeted scheduler; никаких thread/future на AI.
- Настоящие journal, inventory, equipment, skills и currency остаются единственным
  источником истины.
- Все AI flags default-off; allowlists узкие; любое расхождение отклоняется fail-closed.
- Runtime-копия template Cool не сохраняется; account Test остаётся deactivated.

## Последний принятый шаг

2F — explicit, idempotent create и ownership постоянного PERSONAL_COMPANION как
настоящего level-1 `Player` на закрытом AI-host account, вне character slots
owner. Реальный клиент подтвердил один и тот же `player_id=106124` для repeat
create, dismiss/summon, follow, relogin и полного restart; foreign owner был
отклонён, SQL подтвердил один claim/binding/body. Решение и scope:
[ADR-0006](ADR-0006-PRODUCT-COMPANION-CREATION.md) и
[IMPLEMENTATION_PLAN_2F](IMPLEMENTATION_PLAN_2F.md).

Продуктовая Веха 1 закрыта как вертикальный slice: создание, follow/wait,
quest-goal/execution, owner attribution и persistence подтверждены. Этап 3 ещё
не начат.

Принято следующее продуктовое направление для PERSONAL_COMPANION: character
level будет синхронизирован с owner 1:1, без самостоятельного XP и второй native
reward share; gear не зеркалируется, а inventory/equipment остаются отдельным
реальным состоянием без копирования предметов. Это ещё не реализовано и не имеет
технического ADR: прежде нужен отдельный аудит native level/skills/inventory и
account-scoped mechanics.

## Наблюдения для следующих slices

- Follow может кратко блокироваться геодатой; в ручной 2E-проверке companion
  продолжил движение после перемещения owner.
- Combat status сворачивает конкретный quest authorization reason в общий
  `TARGET_NOT_ALLOWED`; отдельный spawn template `210133` был безопасно отклонён
  как отсутствующий в immutable manifest.
- Один shared deactivated host account допустим для текущего starter state, но
  перед реальной inventory/economy progression нужен отдельный аудит
  account-scoped warehouse/state.
- Legacy 2E binding без creation claim при включённом 2F намеренно не
  усыновляется; это fail-closed migration boundary.
