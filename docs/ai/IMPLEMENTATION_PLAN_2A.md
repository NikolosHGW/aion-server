# Этап 2A: PlayerActionGateway и минимальный AI-компаньон

Статус: реализован автоматически; требуется ручная приёмка двумя клиентами

Дата реализации: 2026-07-31

Зависимости:

- [ADR-0001](ADR-0001-SERVER-CONTROLLED-PLAYER.md) принят;
- [этап 1](IMPLEMENTATION_PLAN.md) принят вручную и остаётся regression baseline.

## 1. Scope

Один реальный администратор становится владельцем одного runtime companion.
Companion использует обычный полностью загруженный `Player` как единственное
тело в `World`, не имеет `AionConnection`, появляется рядом с владельцем,
следует за ним, выполняет `stay`/`follow` и безопасно удаляется.

Не входят:

- combat и skills;
- quests и read-only quest planner;
- groups;
- inventory/loot/equipment decisions;
- economy;
- persistence;
- pathfinding и обход препятствий;
- несколько companions.

## 2. Архитектура

```text
CompanionController
  reads -> CompanionContext
  decides -> FOLLOWING | STAYING | BLOCKED | REMOVING
  acts only through -> PlayerActionGateway

PlayerActionGateway
  - checkMovement(destination)
  - startMove(validatedMovement)
  - stopMove()

DefaultPlayerActionGateway
  -> GeoService collision + LoS to owner + LoS to destination
  -> PlayerMoveController.startServerControlledMove()
  -> PlayerMoveTaskManager
  -> existing SM_MOVE broadcast
```

`CompanionController` не обращается к `World`, `PlayerMoveController`,
`PlayerMoveTaskManager`, geodata или packet-коду. Gateway не содержит
заготовок для боя, skills, quests или inventory.

`SyntheticPlayerRuntime` предоставляет один общий
`ServerControlledPlayerRegistry` и один `SyntheticPlayerScheduler`. Registry
разделяет роли `ROUTE_SPIKE` и `COMPANION`, но object ID остаётся глобально
уникальным. Поэтому `//aiplayer` и companion с одним template ID конфликтуют
до `World.storeObject()`; с разными IDs scheduler обслуживает обе роли
round-robin без отдельного потока.

## 3. Feature flags и конфигурация

Все значения по умолчанию безопасны:

```properties
ai.enabled = false
ai.companions.enabled = false

ai.companions.template_account_id = 0
ai.companions.template_character_id = 0
ai.companions.follow_start_distance = 6
ai.companions.follow_stop_distance = 3
ai.companions.follow_offset_distance = 2
ai.companions.spawn_offset_distance = 2
ai.companions.collision_tolerance = 0.75
ai.companions.destination_update_interval_ms = 400
ai.companions.blocked_retry_interval_ms = 1000
```

Обязательные инварианты:

- `follow_start_distance > follow_stop_distance > 0`;
- follow/spawn offsets положительны;
- blocked retry не чаще destination update;
- geodata и can-see должны быть включены;
- companion зависит от `ai.enabled && ai.companions.enabled`, но не от
  `ai.synthetic_players.enabled`.

## 4. Команды

Команда `//companion` имеет административный access level 9:

```text
//companion summon
//companion follow
//companion stay
//companion dismiss
//companion status
```

Владельцем становится реальный connected/spawned `Player`, выполнивший
`summon`. Только этот экземпляр владельца может выполнять follow/stay/dismiss.

## 5. Summon lifecycle

1. Проверить global/companion flags и отсутствие активной роли companion.
2. Проверить connected, exact world identity и spawned state владельца.
3. Проверить template IDs, ownership, DB online marker и runtime-имя.
4. Проверить object ID/имя в `World`, `PlayerContainer`, registry, scheduler и
   movement manager.
5. Загрузить полный `Player` через
   `PlayerService.getPlayer(objectId, account, false)`.
6. Проверить `clientConnection == null`, packet presentation fields и
   отсутствие periodic save tasks.
7. Выбрать точку позади владельца, проверить region, Z, collision и LoS.
8. Изменить имя только в runtime common data.
9. Транзакционно выполнить registry/session registration,
   `World.storeObject`, `World.spawn` и scheduler registration.
10. При любой ошибке выполнить identity-safe cleanup и проверить отсутствие во
    всех пяти runtime registries/managers.

## 6. Follow state machine

```text
summon/follow -> FOLLOWING
stay          -> STAYING
blocked/dead  -> BLOCKED
dismiss       -> REMOVING -> removed
```

- Пока расстояние не больше start distance, остановленный companion не
  начинает движение.
- После превышения start distance gateway направляет его к offset-точке позади
  владельца.
- После начала движения destination обновляется с ограниченной частотой.
- Внутри stop distance движение прекращается.
- При collision либо отсутствии LoS companion останавливается, переходит в
  `BLOCKED` и повторяет проверку не чаще blocked retry interval.
- При смерти владельца companion останавливается в `BLOCKED`.
- При disconnect/logout, уходе владельца из `World` либо смене map/instance
  companion удаляется.
- Companion не телепортируется и не выполняет действий вне физического мира.

## 7. Despawn и persistence boundary

Cleanup выполняется в порядке:

1. `REMOVING`, запрет новых решений;
2. unregister scheduler;
3. stop movement и unregister movement manager;
4. clear target;
5. identity-safe `World.removeObject`;
6. unregister shared registry и companion session;
7. восстановить DB-имя только в detached runtime object;
8. проверить отсутствие в `World`, `PlayerContainer`, registry, scheduler,
   movement manager и companion session.

Owner logout вызывает cleanup до сохранения обычного владельца. Shutdown
сначала удаляет companion и route spike, затем останавливает общий scheduler,
и только после этого запускает штатное сохранение обычных игроков.

Запрещены и не вызываются:

- `PlayerEnterWorldService`;
- `PlayerLeaveWorldService` для companion;
- `PlayerService.storePlayer`;
- DAO save/update;
- DB online marker;
- packet handlers;
- `TaskId.PLAYER_UPDATE` и `TaskId.INVENTORY_UPDATE`.

## 8. Автоматические тесты

Добавлены pure/unit/source-boundary проверки:

- flags и owner preflight;
- правильная owner association;
- follow start/stop и hysteresis;
- stay/follow transition;
- blocked LoS и ограничение retry;
- owner disconnect/death/map-instance change;
- shared scheduler runtime-disable, budget и round-robin;
- gateway API boundary;
- отсутствие direct World/movement/packet calls из controller;
- отсутствие persistence/login/logout/packet-handler calls;
- полнота status;
- явные logout/shutdown cleanup hooks.

Общие тесты этапа 1 продолжают проверять object ID/name collisions,
transaction rollback, idempotent cleanup, route spike, container identity и
отсутствие template persistence.

## 9. Ручная приёмка двумя клиентами

1. Сохранить baseline DB-полей template: `name`, `x`, `y`, `z`, `heading`,
   `world_id`, `online`, account `activated`.
2. Настроить companion template IDs и включить global/companion flags.
3. Оставить `ai.synthetic_players.enabled` в любом состоянии; companion не
   должен от него зависеть.
4. Войти владельцем A и наблюдателем B на открытый участок одной
   map/instance.
5. Выполнить `//companion status`; ожидается `active=false`.
6. Выполнить `//companion summon`; оба клиента должны увидеть одного
   `[AI]`-игрока рядом с A.
7. Проверить active status, `clientConnectionNull=true`,
   `periodicSaveTasks=false`, world/scheduler presence.
8. Идти владельцем по прямой: companion начинает движение только после start
   distance и останавливается внутри stop distance.
9. Стоять и медленно двигаться между stop/start distances: start/stop jitter
   отсутствует.
10. Выполнить `//companion stay`, отойти, убедиться, что companion остаётся.
11. Выполнить `//companion follow`, убедиться, что следование возобновилось.
12. Подвести маршрут к стене/углу: companion переходит в `BLOCKED`, не проходит
    стену и не телепортируется. Проверить `lastBlockedReason`.
13. Ввести B поздно либо выйти/вернуться в visibility radius: позиция
    актуальна, duplicate отсутствует.
14. Сменить A map/instance: companion удаляется с
    `lastRemovalReason=owner-map-or-instance-changed`.
15. Повторить summon и logout/disconnect A: companion исчезает у B.
16. Повторить summon, затем dismiss и повторный dismiss.
17. При использовании того же template запустить `//aiplayer`, затем
    `//companion summon`: второй spawn должен быть отклонён по object ID без
    частичной регистрации.
18. Выключить `ai.companions.enabled` runtime-командой и проверить cleanup.
19. Повторить этап-1 smoke test `//aiplayer`.
20. Сравнить DB baseline: все template/account поля должны совпасть.

## 10. Ограничения и rollback

Ограничения:

- один companion;
- только одна map/instance с владельцем;
- нет обхода препятствия: `BLOCKED` до появления прямого сегмента;
- companion не помогает в бою и не входит в группу;
- owner association и state transient, restart recovery отсутствует;
- общий scheduler пока использует stage-1 параметры budget/period.

Rollback:

1. `ai.companions.enabled=false`;
2. `//companion dismiss` либо graceful shutdown;
3. проверить `active=false` и отсутствие object ID в мире;
4. удалить только companion/gateway classes, command/config/tests и вернуть
   role-aware расширение shared runtime;
5. повторить полный reactor и ручной regression smoke этапа 1;
6. data/SQL rollback не нужен.

После ручной приёмки работа останавливается. Read-only quest planner, combat и
этап 2B требуют отдельного явного подтверждения.
