# Этап 2E: persistence companion binding и goal intent

Статус: принят после автоматической и ручной приёмки

Дата: 2026-08-16

Baseline: `c3fad073c` (`feat(ai): track companion quest execution`).

## Цель и граница

После штатного dismiss/logout/server restart и нового summon exact owner получает
тот же chosen `COMPLETE_QUEST` intent, а status/progress восстанавливается из его
настоящего `QuestState`.

Не входят: automatic summon, background simulation, companion creation, второй goal type,
group, skills, inventory/progression save, много companion одновременно и любая
запись native quest progress из AI code.

## Доказанная текущая граница

- `CompanionGoalSession` хранит offered/chosen только в memory.
- `removeRuntimeCompanion()` намеренно очищает goal/tracker при любом despawn.
- `PlayerQuestListDAO` уже хранит native quest status, vars и complete count.
- В репозитории нет migration framework: fresh schema лежит в
  `game-server/sql/aion_gs.sql`, incremental DDL — в `game-server/sql/update.sql`.
- DAO используют `DatabaseFactory`/JDBC. Для testability новый DAO должен быть
  за узким repository interface; state/restore policy тестируется без MySQL.
- Текущий template companion задаётся config. 2E фиксирует binding в
  отдельной таблице, чтобы следующий product-create slice не менял goal schema.

## Предлагаемая реализация

1. Добавить DDL двух таблиц и транзакционный `CompanionPersistenceDAO`.
2. Добавить immutable records `CompanionBinding`/`PersistedGoalIntent`, repository interface
   и pure `CompanionGoalRestorePolicy`.
3. Канонично кодировать `QuestGoalPlan.semanticFingerprint()` с version `1` и
   хранить SHA-256, а не Java `hashCode`.
4. В `goal choose`: после всех read-only revalidation, но до runtime attach, одной DB
   транзакцией upsert-ить binding+intent. При DB failure вернуть deterministic failure.
5. В `goal clear`: снача удалить intent, затем очистить runtime. При DB failure
   runtime оставить attached, чтобы память и DB не разошлись.
6. После successful summon загрузить exact owner row. Проверить binding,
   role, flags, allowlists, version и rebuilt fingerprint; восстановить chosen session и tracker.
7. Restore не зависит от eligibility для уже START/REWARD/COMPLETE quest. Для
   ещё не принятого quest prerequisite по-прежнему должен быть valid.
8. Dismiss/logout/shutdown очищают только runtime. Никакой DB write в cleanup
   path, чтобы cleanup остался deterministic и не блокировался MySQL.
9. Status/log добавляют `persistenceEnabled`, `persistedGoalPresent`, `restoreResult`,
   `planVersion`, `persistence=true`; секретов и свободного payload нет.

## Feature flag и rollout

`ai.companions.goal_persistence.enabled = false` по умолчанию. При `false` 2A–2D
работают как сейчас и DAO не вызывается. В local bind-mounted config flag включается
только после применения DDL.

Rollback: выключить flag; старые rows остаются dormant. Удаление таблиц не
является частью runtime rollback и делается только отдельной миграцией.

## Автоматическая приёмка

- repository contract: transactional upsert, exact-owner load, explicit delete, duplicate companion rejection;
- fingerprint: deterministic ordering/encoding, version mismatch and single-field drift rejected;
- restore policy: WAITING, START 0..2, READY 3/3, REWARD, COMPLETE;
- missing/foreign/stale/corrupt/disabled config fail-closed без delete и quest mutation;
- choose DB failure leaves runtime unchosen; clear DB failure leaves runtime chosen;
- dismiss/logout/shutdown perform zero writes and re-summon restores;
- source-boundary: нет `QuestState` setters, `PlayerQuestListDAO.store`, reward/inventory save,
  fake connection, packets, scheduler/thread/future;
- fresh schema import и incremental `update.sql` создают одинаковые constraints;
- full Maven reactor и `git diff --check` green.

## Ручная приёмка

Нужен owner, для которого exact allowlisted quest ещё не COMPLETE; Calmer с
завершённым non-repeatable 1102 не подходит для полного restart сценария.

1. Read-only preflight owner/template и пустых AI rows.
2. Summon → today → choose; SQL показывает binding+intent, но не progress.
3. Штатно принять quest и дойти до 1/N.
4. Dismiss, logout или restart game-server; rows не меняются.
5. Login → summon: status сразу показывает restored chosen goal и native 1/N.
6. Завершить/turn-in, restart и проверить restored COMPLETE.
7. Explicit clear удаляет только goal row; native journal и binding не меняются.
8. Postflight подтверждает exact native quest state и отсутствие template writes.

Для существующей local DB применяется только узкий
`game-server/sql/ai_companion_persistence.sql`, а не весь исторический `update.sql`.
После успешного DDL в local `mygs.properties` переключается
`ai.companions.goal_persistence.enabled = true`.

## Фактическая реализация

- fresh schema, historical `update.sql` и узкая local migration создают одинаковые
  binding/goal constraints;
- `CompanionPersistenceDAO` атомарно сохраняет binding+intent и не подавляет
  DB errors;
- persisted plan хранит version 1 и SHA-256 от stable semantic fields; presentation,
  distance и reference coordinate не создают ложный stale;
- `goal choose` сообщает success только после DB commit, `goal clear` не
  расходится с DB при ошибке;
- successful summon выполняет fail-closed restore, а tracker заново читает native
  `QuestState`;
- dismiss/logout/shutdown не вызывают persistence repository;
- status/log показывают presence, restore result и plan version.

Автоматические проверки 2026-08-09 (финально повторены 2026-08-16):

- узкий 2E набор: 22 tests, failures/errors/skips `0/0/0`;
- full Maven reactor: `BUILD SUCCESS`, game-server 156 + commons 1 = 157 tests,
  failures/errors/skips `0/0/0`;
- focused DDL дважды успешно применён в изолированном MySQL 8.4;
- полный fresh `aion_gs.sql` также успешно импортирован штатным Docker-init
  способом; обе foreign keys и unique constraint присутствуют;
- проверены 64-character fingerprint, unique companion binding и FK cascade;
- временный DB container удалён; перед ручной приёмкой рабочая local DB не изменялась;
- `git diff --check`: PASS.

Ручная приёмка 2026-08-12—2026-08-16:

- owner `Feel` (`106323`, обычный account `Nikolos`, access level `0`) получил
  exact command grant; template — `Cool` (`106168`);
- narrow migration применена к local MySQL, persistence flag включён;
- `choose` до native acceptance создал ровно binding+intent с target `1102`,
  version `1` и 64-character fingerprint; restart восстановил
  `CHOSEN_WAITING_ACCEPTANCE`;
- после штатного принятия и progress `1/3` второй restart восстановил
  `ACTIVE_OBJECTIVE`, native `START`, progress `1/3` без shadow progress;
- при `3/3` tracker перешёл в `READY_TO_TURN_IN`; штатная сдача перевела его
  в `COMPLETED`;
- штатный logout сохранил native `1102 COMPLETE`, `complete_count=1`; binding и
  intent сохранили исходные timestamps;
- финальный restart восстановил `COMPLETED`, а `today` вернул
  `ALREADY_COMPLETE`; повторного offer/reward не было;
- dismiss/logout/restart удаляли только runtime session, persistent rows не
  изменялись.

Наблюдения вне blocking scope 2E:

- companion кратко застрял на геодате и получил `OUT_OF_RANGE`, после движения
  owner самостоятельно продолжил FOLLOW;
- один static spawn правильного template `210133` был fail-closed отклонён как
  `TARGET_NOT_IN_IMMUTABLE_MANIFEST`; native owner всё равно штатно завершил
  objective. Это сохраняет safety boundary, но диагностику/manifest coverage
  стоит улучшить в отдельном combat/navigation slice.

## Stop conditions

Остановить реализацию перед изменением central `Player`, quest core, ordinary player save,
созданием shadow progress, automatic quest mutation или сохранением runtime template Cool.
