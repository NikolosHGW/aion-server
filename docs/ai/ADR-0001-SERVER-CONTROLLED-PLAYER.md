# ADR-0001: модель ServerControlledPlayer

Статус: Accepted

Дата: 2026-07-30

Принято владельцем: 2026-07-30

Связанные документы: [архитектура](ARCHITECTURE.md),
[план этапа 1](IMPLEMENTATION_PLAN.md),
[план этапа 2A](IMPLEMENTATION_PLAN_2A.md)

## Контекст

Клиент Aion должен видеть серверный AI именно как игрока. В текущем сервере
тип пакета появления выбирается в
[`PlayerController.see()`](../../game-server/src/com/aionemu/gameserver/controllers/PlayerController.java#L90):
для `Player` отправляется `SM_PLAYER_INFO`, для `Npc` — `SM_NPC_INFO`.

`Player` можно создать и зарегистрировать в `World` без connection, однако
[`Player.isOnline()`](../../game-server/src/com/aionemu/gameserver/model/gameobjects/player/Player.java#L415)
возвращает `clientConnection != null`. Нужна модель, которая:

- не создаёт fake socket;
- остаётся настоящим `Player` для world, packets и игровых сервисов;
- не дублирует тело для companion/citizen;
- допускает общий scheduler;
- минимально меняет существующий сервер.

## Решение

Выбрать композицию над обычным `Player`:

```text
ServerControlledPlayer (runtime aggregate, не VisibleObject)
  - Player player                    # единственный объект в World
  - ServerPlayerController role      # route/companion/citizen
  - SyntheticPlayerState state
  - scheduling/perception metadata
```

`Player` загружается штатной
[`PlayerService.getPlayer()`](../../game-server/src/com/aionemu/gameserver/services/player/PlayerService.java#L102),
не получает `AionConnection`, регистрируется и спавнится отдельным lifecycle
service. Wrapper хранится в registry по object ID. Клиенты видят вложенный
`Player` через штатные `SM_PLAYER_INFO`, `SM_MOVE` и `SM_DELETE`.

`CompanionController` и `CitizenController` реализуют один интерфейс
`ServerPlayerController` и выдают намерения. Общий `PlayerActionGateway`
исполняет намерения по одинаковым игровым правилам. На этапе 1 существует
только временный `RouteController`; бой, квесты, inventory, groups и economy
не подключаются.

Семантика `Player.isOnline()` не переопределяется и не обходится. В этапах,
которые упираются в неё, network presence и world presence разделяются
явными predicates/interfaces с локальными изменениями consumers.

## Рассмотренные варианты

### A. Наследовать ServerControlledPlayer от Player

Преимущества:

- `instanceof Player` автоматически даёт player presentation.
- Можно добавить поля и специализированный controller.
- Удобный compile-time тип для AI-only methods.

Недостатки:

- `PlayerService.getPlayer()` жёстко создаёт `new Player`, поэтому нужен второй
  почти идентичный loader/factory или invasive factory hook.
- Конструктор `Player` жёстко создаёт `PlayerController` и
  `PlayerMoveController`.
- Наследуется ложная связь `isOnline == connection`; безопасно переопределить
  её нельзя, потому что `PacketSendUtility` после `true` разыменует null
  connection.
- Persistence/services могут случайно начать ветвиться по subtype.

Риски:

- расхождение инициализации обычного и synthetic player;
- Liskov violations вокруг login/logout и network identity;
- постепенное накопление overrides.

Объём: средний на spike, высокий к бою/persistence.

Решение: отклонено.

### B. Композиция/адаптер над Player

Преимущества:

- В `World` остаётся точно тот же `Player`, который ожидают packets, quests,
  groups, inventory и combat services.
- Полная загрузка переиспользует `PlayerService.getPlayer`.
- Нет fake connection и client handshake.
- Role controllers отделены от тела; companion/citizen не дублируются.
- Можно вводить domain adapters постепенно, только в местах packet coupling.

Недостатки:

- Wrapper и world object имеют разные object identity; lifecycle обязан
  держать registry и `World` согласованными.
- Некоторые protected movement детали требуют узкой новой entry point.
- Существующие consumers `isOnline()` всё равно надо аудировать по этапам.

Риски:

- ghost wrapper после неудачного despawn;
- обход gateway прямым доступом к `Player`;
- template/account collision.

Объём: низкий для этапа 1, средний для следующих vertical slices.

Решение: выбрано.

### C. Использовать существующую NPC/AI-основу как тело

Преимущества:

- Готовые `AIState/AISubState`, event handlers, walking/attack managers.
- `NpcMoveController` уже использует geo corrections.
- Существуют общие movement task managers.

Недостатки:

- Known-list отправляет `SM_NPC_INFO`, а не `SM_PLAYER_INFO`.
- NPC templates/stats/skills/equipment не являются player model.
- Quest, group, broker и многие item APIs принимают `Player`.
- NPC pathfinding фактически отсутствует; есть только движение к точке и
  collision/Z correction.
- `Creature.ai` final; продуктовая подмена reflection неприемлема.

Риски:

- клиент видит NPC или нестабильный гибрид;
- двойная реализация progression/economy;
- большой protocol/domain rewrite.

Объём: очень высокий.

Решение: отклонено как тело; state-machine идеи можно переиспользовать.

### D. Добавить ControlMode/ControlStrategy прямо в Player

Преимущества:

- Одна identity без wrapper registry.
- Можно системно разделить client/world presence.
- Потенциально чистая долгосрочная модель.

Недостатки:

- Меняет центральную сущность для каждого живого игрока.
- Требует сразу затронуть login/logout, restrictions, groups, tasks, audit,
  debug и packet routing.
- Риск regression непропорционален техническому spike.

Риски:

- изменение поведения обычных игроков при выключенных flags;
- большой diff, сложный rollback.

Объём: высокий.

Решение: отложено. Может стать эволюцией после доказанного spike и карты всех
presence consumers.

## Последствия

Положительные:

- Клиентский protocol не меняется.
- Один `Player` остаётся source of truth для координат, appearance и stats.
- Нет отдельного потока и сетевого объекта на AI.
- Этап 1 можно удалить без schema migration.

Отрицательные:

- Нужен строгий lifecycle state machine.
- Этап 1 доказывает только presentation/movement/despawn, не пригодность всех
  player domains без connection.
- Перед item/group/interaction этапами потребуется controlled refactoring
  `isOnline` consumers.

## Инварианты реализации

1. Ни один `ServerControlledPlayer` не имеет `AionConnection`.
2. В `World` регистрируется вложенный обычный `Player`, не wrapper и не NPC.
3. Wrapper создаётся до `World.storeObject` и удаляется после
   `World.removeObject`.
4. Все actions проходят через gateway; role controller не меняет БД/storage.
5. Tick допустим только для spawned player в активном registry.
6. Выключенные flags не меняют обычный login, packets или NPC AI.
7. Этап 1 не вызывает `PlayerLeaveWorldService` и не сохраняет transient state.

## Подтверждение решения ручным тестом

2026-07-31 владелец проекта принял этап 1 после проверки двумя настоящими
клиентами. Тест подтвердил:

- вложенный обычный `Player` отображается клиентам как player model с
  runtime-префиксом `[AI]`, несмотря на `clientConnection == null`;
- штатные player packets обеспечивают непрерывное движение, корректные heading
  и анимацию, а поздний наблюдатель получает актуальную позицию;
- повторное пересечение границы видимости не создаёт duplicate;
- spawn/despawn lifecycle и повторный despawn корректны;
- runtime-имя, позиция, heading, world и online marker не записываются в
  DB-шаблон, а закрытый template account остаётся деактивированным;
- у обычных игроков в проведённом сценарии регрессий не обнаружено.

Эти результаты подтверждают выбранную композиционную модель для presentation,
movement и transient lifecycle этапа 1. Они не доказывают безопасность
connection-dependent доменов будущих этапов: combat, quests, groups, inventory
operations и economy по-прежнему должны подключаться через отдельный gateway и
проходить собственную приёмку.

## Условия пересмотра ADR

Решение пересматривается, если spike покажет хотя бы одно:

- клиент не принимает `SM_PLAYER_INFO`/movement state для player без self
  connection;
- полная инициализация без login orchestration требует копировать значительную
  часть `PlayerEnterWorldService`;
- wrapper lifecycle нельзя сделать idempotent без изменений центральных
  world APIs;
- следующие два vertical slices требуют массового branching по registry.
