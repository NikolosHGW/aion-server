# Минимальный план этапа 1: один видимый серверный игрок

Статус: этап 1 принят вручную владельцем проекта 2026-07-31

Зависимость:
[ADR-0001](ADR-0001-SERVER-CONTROLLED-PLAYER.md) принят владельцем

Scope: только технический spike этапа 1 из мастер-спецификации

## 1. Результат этапа

Администратор одной командой создаёт одного runtime-персонажа с отображаемым
префиксом `[AI]`. Два настоящих клиента подтвердили, что видят его как игрока.
Он циклически проходит 3–5 близких точек, передаёт координаты, heading и
анимацию, корректно появляется для позднего наблюдателя и удаляется у всех.

### 1.1. Результаты ручной приёмки

Владелец проекта выполнил сценарий двумя клиентами и 2026-07-31 подтвердил:

- runtime-объект отображается как обычный `Player` с префиксом `[AI]`;
- `clientConnection == null`;
- движение непрерывное, heading и анимации корректны;
- поздний наблюдатель получает актуальную позицию;
- выход и возвращение в радиус видимости не создают duplicate;
- `despawn` и повторный `despawn` работают корректно;
- после повторного цикла spawn/despawn поля DB-шаблона `name`, `x`, `y`, `z`,
  `heading`, `world_id` и `online` полностью совпадают с исходными;
- template account сохранил `activated = 0`;
- у обычных игроков не обнаружено регрессий.

Ручная приёмка подтверждает presentation, movement, visibility lifecycle и
отсутствие persistence для технического spike. Она не расширяет scope этапа 1
на бой, квесты, группы, companion/citizen behavior или экономику.

Не входят:

- бой и навыки;
- квесты;
- companion/citizen behavior;
- группы;
- лут, inventory management и equipment decisions;
- persistence AI state;
- broker/economy;
- несколько или массовый spawn;
- полноценный pathfinding.

## 2. Подтверждённые решения

Владелец подтвердил:

1. ADR: обычный `Player` как world body + композиционный
   `ServerControlledPlayer`.
2. Для spike используется отдельный заранее созданный template character на
   закрытом account; IDs задаются config. Команда создаёт его runtime-копию,
   но не новую DB-запись.
3. Runtime-копия получает `[AI]` только в памяти и не сохраняется.
4. Временная admin-команда называется `//aiplayer`, чтобы не конфликтовать с
   существующей NPC-командой `//ai`.
5. Маршрут строится из конфигурируемых offsets/точек рядом с администратором;
   заблокированный сегмент отклоняется без телепорта.

Если требуется, чтобы команда создавала новую постоянную DB-запись, это
расширяет этап 1 schema/account lifecycle и должно быть согласовано отдельно.

### 2.1. Обязательные предохранители

До `World.storeObject()`:

1. Проверить, что object ID шаблонного персонажа отсутствует одновременно в
   `World`, `PlayerContainer`, registry, scheduler и movement manager.
2. Сформировать итоговое runtime-имя, проверить его длину и отсутствие игрока
   с таким именем до изменения runtime-копии.
3. Повторить проверки object ID и runtime-имени непосредственно перед
   `World.storeObject()`, чтобы сузить окно гонки.
4. `PlayerContainer.add()` обязан быть атомарным относительно индексов ID и
   имени: неудача второго индекса откатывает только добавляемый объект.

Lifecycle:

1. Любое исключение между регистрацией wrapper, `World.storeObject()` и
   `World.spawn()` запускает полный identity-safe rollback.
2. Rollback удаляет объект только когда конкретная запись принадлежит
   создаваемому runtime-экземпляру, и не затрагивает настоящего игрока с
   конфликтующим именем или ID.
3. После rollback проверяется отсутствие runtime-экземпляра в `World`,
   `PlayerContainer`, registry, scheduler и `PlayerMoveTaskManager`.
4. Shutdown и выключение feature flag останавливают тики, движение и удаляют
   runtime-AI без persistence.

Запрещено:

- вызывать `PlayerEnterWorldService`, `PlayerLeaveWorldService`,
  `PlayerService.storePlayer()` и любые packet handlers;
- назначать `TaskId.PLAYER_UPDATE` или `TaskId.INVENTORY_UPDATE`;
- устанавливать DB online marker;
- сохранять либо переносить runtime-имя, координаты или состояние в
  DB-шаблон.

`//aiplayer status` показывает object ID, DB-имя шаблона, runtime-имя,
lifecycle state, `clientConnection == null`, наличие в `World`, `isSpawned`,
регистрацию в scheduler и movement manager, map/instance и текущую точку
маршрута.

## 3. Предлагаемые изменения файлов

Точный список уточняется после подтверждения, но ожидаемый минимальный diff:

| Файл | Изменение |
|---|---|
| `game-server/src/com/aionemu/gameserver/configs/main/AIConfig.java` | Флаги, template account/character IDs, tick budget и stage-1 route config. |
| `game-server/config/main/ai.properties` | Все AI flags `false`, безопасные defaults. |
| `game-server/config/administration/commands.properties` | Access level для `aiplayer`. |
| `game-server/data/handlers/admincommands/AiPlayer.java` | `spawn`, `despawn`, `status`; только делегирование service. |
| `game-server/src/com/aionemu/gameserver/services/ai/ServerControlledPlayer.java` | Runtime aggregate и lifecycle state. |
| `game-server/src/com/aionemu/gameserver/services/ai/ServerControlledPlayerRegistry.java` | Единственность, lookup и idempotent registration/removal. |
| `game-server/src/com/aionemu/gameserver/services/ai/ServerControlledPlayerService.java` | Load/preflight/spawn/despawn orchestration без connection. |
| `game-server/src/com/aionemu/gameserver/services/ai/RouteController.java` | Только 3–5 точек и переход к следующей. |
| `game-server/src/com/aionemu/gameserver/services/ai/SyntheticPlayerScheduler.java` | Один round-robin scheduler с budget; один AI в spike. |
| `game-server/src/com/aionemu/gameserver/controllers/PlayerController.java` | Для runtime-AI не запускать self-side quest/drop/zone hooks; observers по-прежнему получают player packets. |
| `game-server/src/com/aionemu/gameserver/controllers/movement/PlayerMoveController.java` | Узкая server-controlled start/stop entry point поверх существующей интерполяции. |
| `game-server/src/com/aionemu/gameserver/services/player/PlayerService.java` | Backward-compatible read-only load mode без создания отсутствующих persistent defaults. |
| `game-server/src/com/aionemu/gameserver/dao/AccountPassportsDAO.java` | В read-only load mode не создавать отсутствующую строку stamps. |
| `game-server/src/com/aionemu/gameserver/dao/PlayerLifeStatsDAO.java` | В read-only load mode не создавать отсутствующую строку life stats. |
| `game-server/src/com/aionemu/gameserver/taskmanager/tasks/PlayerMoveTaskManager.java` | Identity-safe registration/removal и read-only status query. |
| `game-server/src/com/aionemu/gameserver/world/container/PlayerContainer.java` | Атомарные индексы ID/имени и identity-safe rollback. |
| `game-server/src/com/aionemu/gameserver/services/DebugService.java` | Не считать зарегистрированного synthetic player потерявшим connection. |
| `game-server/src/com/aionemu/gameserver/ShutdownHook.java` | Удалить runtime-AI без сохранения до shutdown обычных игроков. |
| `game-server/test/com/aionemu/gameserver/services/ai/*Test.java` | Flags, route, scheduler budget, lifecycle/idempotent removal tests. |

Не планируются изменения `AionConnection`, `SM_PLAYER_INFO`, `KnownList`,
`PlayerEnterWorldService`, `PlayerLeaveWorldService`, quest/combat/broker кода
или SQL.

## 4. Последовательность реализации

### 4.1. Feature flags и command guard

Добавить defaults:

```properties
ai.enabled=false
ai.synthetic_players.enabled=false
ai.companions.enabled=false
ai.citizens.enabled=false
ai.economy.enabled=false
```

`//aiplayer spawn` обязан отказать, если global или synthetic flag выключен.
При runtime reload `true -> false` service прекращает ticks и удаляет
активного AI. Companion/citizen/economy flags пока ни на что не влияют.

### 4.2. Загрузка без клиента

`ServerControlledPlayerService.spawn(admin)`:

1. Проверяет flags и отсутствие уже активного spike player.
2. Загружает отдельный `Account` через `AccountService.loadAccount`.
3. Находит configured template character и вызывает read-only overload
   `PlayerService.getPlayer(characterId, account, false)`.
4. Проверяет `player.getClientConnection() == null`.
5. Проверяет обязательные packet fields: appearance, settings, abyss rank,
   motions, stats, equipment, known-list и position.
6. Меняет имя только у runtime `PlayerCommonData` на `[AI]<baseName>`.
7. Создаёт wrapper/route и регистрирует его до добавления тела в `World`.
8. Ставит player в map/instance администратора на безопасную начальную точку.
9. Вызывает `World.storeObject(player)`, затем `World.spawn(player)`.

Не устанавливаются `PlayerCommonData.online`, `AionConnection.activePlayer`
или DB online marker. Не отправляется `SM_PLAYER_SPAWN`, потому что у AI нет
собственного клиента и карты для загрузки.

### 4.3. Route preflight

Маршрут содержит 3–5 точек в одном map/instance. Для каждой точки:

- `World.createPosition` должен найти map region;
- `GeoService.getZ` должен вернуть валидный Z;
- collision primitive не должен обрезать сегмент;
- start/end должны иметь прямой LoS;
- длина сегмента ограничена config;
- все точки должны оставаться достаточно близко для наблюдения двумя
  клиентами.

Ошибка любой точки отменяет spawn до `World.storeObject`. После частичной
регистрации выполняется полный cleanup.

### 4.4. Движение

Добавить в `PlayerMoveController` явную server-only операцию:

```text
startServerMove(destination, heading)
  -> setNewDirection
  -> movementMask = NPC_STARTMOVE (POSITION|MANUAL|ABSOLUTE)
  -> set moving state
  -> broadcast SM_MOVE to sighted observers
  -> register in PlayerMoveTaskManager
```

Существующий `PlayableMoveController.moveToDestination()` интерполирует
позицию по реальной movement speed. Scheduler проверяет достижение текущей
точки; при достижении вызывает штатный stop, затем выбирает следующую. Heading
вычисляется через `PositionUtil.getHeadingTowards`.

Нельзя:

- вызывать `CM_MOVE.runImpl()`;
- создавать synthetic client packet;
- менять координаты только редкими скачками;
- телепортировать к следующей точке;
- использовать отдельный movement thread.

На каждом старте/повороте/остановке клиенты получают `SM_MOVE`. Server position
обновляется с тем же cadence, что другие playable server movements.

### 4.5. Поздний наблюдатель

Специального кода не добавлять. При spawn второго клиента:

1. его `KnownList.update()` находит synthetic `Player`;
2. `PlayerController.see()` отправляет `SM_PLAYER_INFO`;
3. packet содержит текущие coordinates, heading, movement mask и vector;
4. motions отправляются следующим packet.

Автоматический/ручной тест обязан доказать это во время движения, а не только
в состоянии покоя.

### 4.6. Удаление

`//aiplayer despawn`:

1. CAS `ACTIVE -> REMOVING`;
2. снять wrapper с scheduler и запретить новые actions;
3. остановить move controller и убрать player из `PlayerMoveTaskManager`;
4. очистить target;
5. вызвать `World.removeObject(player)`;
6. проверить отсутствие объекта в `World`;
7. удалить registry reference в `finally`;
8. перейти `REMOVED`.

Метод повторного удаления возвращает «already absent», не бросает исключение.
`PlayerLeaveWorldService.leaveWorld()` не вызывается. Transient position/name
не сохраняются.

## 5. Автоматические тесты

Минимальный набор:

1. `flagsDisabled_rejectsSpawnWithoutWorldMutation`.
2. `secondSpawn_isRejectedAndDoesNotCreateDuplicate`.
3. `routeRequiresThreeToFivePoints`.
4. `routeRejectsInvalidRegionNaNZAndBlockedSegment`.
5. `movementUsesConfiguredSpeedAndUpdatesHeading`.
6. `routeLoopsAndStopsAtEachWaypoint`.
7. `schedulerRespectsActionAndTimeBudget`.
8. `despawnStopsTicksBeforeWorldRemoval`.
9. `despawnIsIdempotent`.
10. `partialSpawnFailureRollsBackRegistryAndWorld`.
11. `offlineElapsedTimeDoesNotAdvanceRoute`.
12. `disabledAtRuntimeRemovesActiveSyntheticPlayer`.

Pure route/scheduler logic тестируется без запуска сервера. World integration
использует минимальные test fixtures; если packet serialization невозможно
изолировать без реального `AionConnection`, late-join presentation остаётся
обязательным ручным acceptance test и это явно фиксируется.

Команда проверки:

```bash
mvn -B -T 4 -Dmaven.source.skip=true -Dmaven.test.skip=false test
```

## 6. Наблюдаемость этапа 1

Структурированный lifecycle/movement log:

```text
aiId, playerObjectId, lifecycleState, mapId, instanceId,
waypointIndex, x, y, z, heading, movementMask, durationMs, result
```

Счётчики:

- active wrappers (должно быть 0 или 1);
- scheduler tick count/duration/max и budget misses;
- movement start/stop/position updates;
- generated `SM_PLAYER_INFO`/`SM_MOVE`/`SM_DELETE` for synthetic object;
- spawn failures и cleanup failures;
- registry/world mismatch;
- connection unexpectedly non-null.

30-минутный тест считается неуспешным при повторяющихся warnings/errors,
registry leak, duplicate object, stuck movement или росте scheduler queue.

## 7. Ручная проверка двумя клиентами — выполнена

Предусловия:

- отдельный template account/character configured и недоступен игрокам;
- оба flags включены;
- два обычных клиента находятся одной фракции на безопасной открытой карте;
- один персонаж имеет access к `//aiplayer`.

Шаги:

1. Войти клиентом A (администратор), запомнить map/channel/координаты.
2. Убедиться через `//aiplayer status`, что active count равен 0.
3. Выполнить `//aiplayer spawn`.
4. Проверить, что появился ровно один `[AI]` объект и визуально он является
   player model: player nameplate, appearance и equipment; не NPC dialog/type.
5. Наблюдать полный цикл 3–5 точек:
   - нет телепортов;
   - heading меняется к следующей точке;
   - run/walk animation соответствует движению;
   - после остановки нет sliding;
   - server status coordinates совпадают с видимой позицией.
6. Не выходя клиентом A, войти клиентом B и подойти в радиус во время движения.
7. Проверить, что B видит того же object ID/имя и актуальную точку маршрута,
   а не spawn origin; A и B видят одинаковое направление/анимацию.
8. Уйти B за радиус и вернуться; AI исчезает/появляется без duplicate.
9. Перезайти B и повторить проверку позднего появления.
10. Телепортировать B на другую карту и обратно; duplicate отсутствует.
11. Выполнить `//aiplayer despawn` на A во время движения.
12. Проверить мгновенное корректное исчезновение у A и B, отсутствие ghost
    nameplate/target и `active count = 0`.
13. Повторить despawn; команда безопасно сообщает, что объекта нет.
14. Выставить `ai.synthetic_players.enabled=false`, reload/restart config и
    проверить, что spawn запрещён.
15. Оставить AI включённым на 30 минут в отдельном прогоне; проверить логи,
    CPU/heap, thread count и отсутствие connection warning.
16. После удаления проверить обычное движение обоих клиентов и NPC.

## 8. Критерии приёмки

- Реализован только один synthetic player.
- У него всегда `clientConnection == null`.
- Оба клиента получают player presentation.
- Маршрут имеет 3–5 точек, движение непрерывно и использует штатную скорость.
- Late observer получает актуальное состояние.
- Despawn очищает оба клиента, `World`, scheduler и registry.
- Повторные spawn/despawn не создают duplicate и не падают.
- 30 минут не дают потока warnings/errors.
- При обоих flags `false` обычный сервер ведёт себя как baseline.
- Maven reactor и все тесты проходят.

Этап 1 принят владельцем 2026-07-31. Бой, квесты, компаньон, экономика и
массовый spawn не начаты и требуют отдельного согласования минимального scope
этапа 2.

## 9. Rollback этапа 1

1. Отключить оба flags.
2. Выполнить despawn либо graceful restart.
3. Убедиться, что active registry count равен 0.
4. При необходимости откатить только перечисленные в разделе 3 файлы.
5. SQL rollback отсутствует: spike не создаёт schema и не сохраняет runtime
   состояние template character.
6. Повторить login/movement/known-list smoke test двумя обычными клиентами.
