# Этап 2F: product create/ownership lifecycle

Статус: принят после автоматической и ручной приёмки

Дата: 2026-09-11

Baseline: `3dd9e5d15`.

## Цель

Обычный игрок один раз создаёт постоянного PERSONAL_COMPANION как настоящий
server-controlled `Player`. Повторные create/summon/relogin/restart возвращают
тот же player ID; owner account slots не используются.

## Граница

Входят: explicit create, deterministic identity reservation, стандартное
level-1 Player creation на закрытом host account, immutable claim+binding,
idempotent recovery, summon resolution и диагностика ownership.

Не входят: Milestone 2 profile/editor/progression, новые combat skills, group,
автономные жители, удаление/re-roll companion, автоматический summon и economy.

## Реализация

- `CompanionCreationService` оркестрирует чистую policy, persistence repository
  и узкий `CompanionPlayerCreationGateway`.
- Gateway вызывает `PlayerService.newPlayer/storeNewPlayer`, но не
  `CM_CREATE_CHARACTER`, login-server API или character-slot checks.
- `ai_companion_creation_claims` делает non-atomic multi-DAO player creation
  возобновляемым: reservation существует до создания body, а финализация
  проверяет exact persisted body и атомарно закрепляет binding.
- `CompanionCreationPolicy` требует exact owner, host, deterministic name,
  race/gender, starting class, offline marker и наличие базовых persistent rows.
- При 2F goal сохраняется только для уже существующего exact binding; goal save
  больше не имеет права незаметно перепривязать companion.
- `create` не spawn-ит; `summon` не создаёт. Это делает side effects явными.

## Конфигурация и rollout

По умолчанию:

```properties
ai.companions.creation.enabled = false
ai.companions.creation.host_account_id = 0
ai.companions.creation.host_account_name =
```

Local manual environment использует деактивированный `Test` id `13` только
после `game-server/sql/ai_companion_creation.sql`. Также обязательны global,
companions и goal persistence flags.

## Автоматические критерии

- deterministic name стабилен, короток, различается по owner;
- first create создаёт один body, repeat возвращает тот же ID;
- reserved crash-recovery не создаёт duplicate;
- missing claim, foreign binding, wrong host/body/race/class/online или
  incomplete standard creation отклоняются fail-closed;
- schema имеет PK owner, UNIQUE name/body и restrictive companion FK;
- creation path не содержит connection/client packet, account slot/login DB,
  direct XP/reward mutation, World spawn или отдельный scheduler;
- 2A–2E tests и полный reactor зелёные при явном `maven.test.skip=false`.

## Ручная приёмка

Проверен новый ordinary owner без legacy binding (`Feel` не использовался: его
accepted 2E row намеренно связывает с `Cool`). First `create` создал companion;
repeat `create` вернул ту же личность без дублей. `summon`/`dismiss`/`summon`,
follow, logout/relogin и полный restart GameServer сохранили `player_id=106124`.
Другой персонаж не смог присвоить body. SQL подтвердил по одному claim, binding
и body (`body_duplicates=1`); body хранится на deactivated host account `Test`
(`account_id=13`), а slots account `Nikolos` не затронуты.

Ручной runtime включал оба флага только после focused DDL. После приёмки
repository-local default возвращён к `false`, как и в базовом config: это
существующий fail-safe/opt-in контракт, а не новая policy.
