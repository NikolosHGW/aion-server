# Архитектура серверно управляемых игроков

Статус: аудит этапа 0

Дата аудита: 2026-07-30

Мастер-спецификация: [`AION_AI_MVP_SPEC.md`](../../AION_AI_MVP_SPEC.md)

Решение: [ADR-0001](ADR-0001-SERVER-CONTROLLED-PLAYER.md)

## 1. Границы аудита

Этот документ фиксирует устройство текущей рабочей копии Beyond Aion 4.8 и
архитектурное решение для будущего `ServerControlledPlayer`. В рамках этапа 0
Java-код, конфигурация и игровое поведение не менялись.

Исследована ветка `feature/ai-mvp`, commit
`4ffcf5602a9d2bf13c5a042ff693ba70ff3c8e4b`. Репозиторий является Maven
reactor из `commons`, `chat-server`, `game-server` и `login-server`;
интересующая логика находится преимущественно в `game-server` (2306 Java-файлов).

До аудита рабочая копия уже содержала изменения пользователя в
`bootstrap.md`, docker-compose и двух `my*.properties`, а также неотслеживаемую
мастер-спецификацию. Они не изменялись. `AGENTS.md` в репозитории и его
родительском каталоге не найден.

### 1.1. Baseline сборки и тестов

Локальный запуск Maven невозможен: в окружении отсутствует рабочая Java,
и Maven завершается сообщением `The JAVA_HOME environment variable is not
defined correctly`. Это ограничение среды, а не обнаруженная ошибка проекта.

Baseline зафиксирован в чистом одноразовом контейнере JDK 25:

```bash
docker run --rm \
  -v /home/nikolos/dev/aion/aion-server:/src \
  -w /src \
  maven:3-eclipse-temurin-25 \
  mvn -B -T 4 -Dmaven.source.skip=true -Dmaven.test.skip=false test
```

Результат: `BUILD SUCCESS`, все четыре модуля reactor успешны; выполнено 13
тестов, failures/errors/skips — `0/0/0`, Maven wall clock — `52.506 s`.
`chat-server` и `login-server` не содержат выполненных тестов. Эта проверка
выполнена до создания документов; продуктовый код и пользовательские файлы не
изменялись.

## 2. Краткий вывод

Серверно управляемый персонаж может существовать без фиктивного сокета:

1. Его игровым телом остаётся обычный, полностью загруженный
   [`Player`](../../game-server/src/com/aionemu/gameserver/model/gameobjects/player/Player.java#L84).
2. В его `Player.clientConnection` остаётся `null`.
3. Его представляет композиционный runtime-агрегат `ServerControlledPlayer`,
   который хранит ссылку на `Player`, контроллер роли и состояние планирования.
4. `World`, `KnownList` и исходящие пакеты видят именно `Player`, поэтому
   реальные клиенты получают `SM_PLAYER_INFO`, а не `SM_NPC_INFO`.
5. Команды AI вызывают доменные сервисы или узкие адаптеры над ними, а не
   эмулируют входящие клиентские пакеты.

Это возможно потому, что
[`PacketSendUtility.sendPacket(Player, ...)`](../../game-server/src/com/aionemu/gameserver/utils/PacketSendUtility.java#L74)
отправляет пакет только когда у адресата есть соединение. Пакеты *об AI*
отправляются соединениям наблюдателей через known-list, а не соединению самого
AI.

Главный технический долг — текущий
[`Player.isOnline()`](../../game-server/src/com/aionemu/gameserver/model/gameobjects/player/Player.java#L415)
означает «имеет `AionConnection`», а не «физически присутствует в `World`».
Из-за этого серверный игрок уже может быть видим и двигаться, но многие более
поздние операции считают его офлайн. Этап 1 не должен маскировать это fake
connection; разделение понятий `hasClientConnection` и `isWorldActive` следует
делать по мере подключения доменных операций в следующих этапах.

## 3. Карта текущей реализации

### 3.1. Player, создание и загрузка

| Область | Конкретная точка | Наблюдение |
|---|---|---|
| Сущность игрока | [`Player(PlayerAccountData, Account)`](../../game-server/src/com/aionemu/gameserver/model/gameobjects/player/Player.java#L192) | Создаёт `PlayerController`, `PlayerMoveController`, equipment, inventory, cooldown containers и stat containers. |
| Данные аккаунта | [`Account`](../../game-server/src/com/aionemu/gameserver/model/account/Account.java#L20), [`PlayerAccountData`](../../game-server/src/com/aionemu/gameserver/model/account/PlayerAccountData.java#L20) | `Player` требует настоящий доменный объект аккаунта, но аккаунт не обязан иметь сетевую сессию. |
| Создание персонажа | [`CM_CREATE_CHARACTER.runImpl()`](../../game-server/src/com/aionemu/gameserver/network/aion/clientpackets/CM_CREATE_CHARACTER.java#L45), [`PlayerService.newPlayer()`](../../game-server/src/com/aionemu/gameserver/services/player/PlayerService.java#L187) | Packet handler собирает common data; сервис назначает стартовую позицию, навыки, предметы и mailbox. |
| Сохранение нового | [`PlayerService.storeNewPlayer()`](../../game-server/src/com/aionemu/gameserver/services/player/PlayerService.java#L69), [`PlayerDAO.saveNewPlayer()`](../../game-server/src/com/aionemu/gameserver/dao/PlayerDAO.java#L86) | Создание связано с таблицами обычных персонажей. |
| Загрузка аккаунта | [`AccountService.loadAccount()`](../../game-server/src/com/aionemu/gameserver/services/AccountService.java#L75), [`loadPlayerAccountData()`](../../game-server/src/com/aionemu/gameserver/services/AccountService.java#L84) | Загружает common data, appearance и видимую экипировку. |
| Полная загрузка | [`PlayerService.getPlayer()`](../../game-server/src/com/aionemu/gameserver/services/player/PlayerService.java#L102) | Создаёт обычный `Player`, позицию, known-list, skills, quests, inventory, equipment, effects, cooldowns, stats, motions и settings. Это безопасная фабрика тела AI. |

Для этапа 1 безопаснее материализовать runtime-игрока из отдельного
предварительно созданного шаблонного персонажа через `AccountService` и
`PlayerService.getPlayer()`, чем копировать вручную длинный список
инициализации. Команда «spawn» создаёт новый runtime-объект в мире, не создаёт
сокет и не запускает клиентский login handshake. В spike состояние не
сохраняется обратно, а отображаемое имя `[AI] ...` меняется только у
загруженной runtime-копии до регистрации в `World`.

Создание отдельной постоянной AI-записи и её схема хранения относятся к более
позднему этапу и требуют отдельного решения.

### 3.2. Вход, выход, spawn и despawn

Обычный клиент проходит два разделённых шага:

```text
AionConnection + account
  -> PlayerEnterWorldService.enterWorld()
  -> PlayerService.getPlayer()
  -> player.setClientConnection(connection)
  -> connection.setActivePlayer(player)
  -> World.storeObject(player)
  -> клиент получает стартовые пакеты и SM_PLAYER_SPAWN
  -> клиент присылает CM_LEVEL_READY
  -> World.spawn(player)
  -> known-list, QuestEngine и onEnterWorld hooks
```

Точки реализации:

- [`PlayerEnterWorldService.enterWorld()`](../../game-server/src/com/aionemu/gameserver/services/player/PlayerEnterWorldService.java#L89)
  проверяет аккаунт, duplicate object, bans, passkey и online marker.
- Приватный login path устанавливает соединение и регистрирует объект через
  [`World.storeObject()`](../../game-server/src/com/aionemu/gameserver/services/player/PlayerEnterWorldService.java#L197).
- [`SM_PLAYER_SPAWN`](../../game-server/src/com/aionemu/gameserver/network/aion/serverpackets/SM_PLAYER_SPAWN.java#L14)
  сообщает *самому входящему клиенту*, какую карту загрузить; это не пакет
  появления другого игрока.
- [`CM_LEVEL_READY.runImpl()`](../../game-server/src/com/aionemu/gameserver/network/aion/clientpackets/CM_LEVEL_READY.java#L44)
  вызывает [`World.spawn(activePlayer)`](../../game-server/src/com/aionemu/gameserver/network/aion/clientpackets/CM_LEVEL_READY.java#L64).
- [`World.spawn()`](../../game-server/src/com/aionemu/gameserver/world/World.java#L283)
  добавляет объект в map region и обновляет known-list.
- [`World.despawn()`](../../game-server/src/com/aionemu/gameserver/world/World.java#L305)
  удаляет объект из region и очищает двусторонние known-lists.
- [`World.removeObject()`](../../game-server/src/com/aionemu/gameserver/world/World.java#L99)
  атомарно выполняет despawn, controller delete hook и удаление из глобальных
  контейнеров.

Обычный logout находится в
[`PlayerLeaveWorldService.leaveWorld()`](../../game-server/src/com/aionemu/gameserver/services/player/PlayerLeaveWorldService.java#L63):
он снимает connection, отключает сервисы, сохраняет effects/cooldowns/player,
удаляет объект и в конце вызывает `con.setActivePlayer(null)`. Этот метод
нельзя вызывать для AI с `null` connection.

Для AI нужен отдельный симметричный lifecycle service с минимальным набором
hooks. На этапе 1:

```text
admin command
  -> проверить ai.enabled && ai.synthetic_players.enabled
  -> загрузить полностью инициализированный Player без connection
  -> создать ServerControlledPlayer wrapper и зарегистрировать его
  -> задать валидную позицию/маршрут
  -> World.storeObject(player)
  -> World.spawn(player)
  -> общий scheduler ведёт по точкам
  -> stop: запретить новые тики
  -> остановить движение и снять scheduled registration
  -> World.removeObject(player)
  -> удалить registry references
```

Не вызываются `PlayerEnterWorldService`, `CM_LEVEL_READY.runImpl()` или
`PlayerLeaveWorldService`: они являются orchestration клиентской сессии.

### 3.3. Как клиент видит другого игрока

[`KnownList`](../../game-server/src/com/aionemu/gameserver/world/knownlist/KnownList.java#L25)
поддерживает двустороннее знакомство объектов.

1. [`KnownList.update()`](../../game-server/src/com/aionemu/gameserver/world/knownlist/KnownList.java#L43)
   находит объекты в соседних regions.
2. Дистанция берётся как максимум visible distance двух объектов;
   базовый [`VisibleObject.getVisibleDistance()`](../../game-server/src/com/aionemu/gameserver/model/gameobjects/VisibleObject.java#L247)
   возвращает 95 метров.
3. При появлении `PlayerController.see()` распознаёт объект через
   `instanceof Player`.
4. [`sendPlayerInfoPackets()`](../../game-server/src/com/aionemu/gameserver/controllers/PlayerController.java#L122)
   отправляет наблюдателю:
   - [`SM_PLAYER_INFO`](../../game-server/src/com/aionemu/gameserver/network/aion/serverpackets/SM_PLAYER_INFO.java#L21);
   - активные motions;
   - ride/stance при необходимости.
5. `SM_PLAYER_INFO` сериализует appearance, equipment, race/class, level,
   положение, heading, скорость и текущее движение из настоящего `Player`.
6. При потере видимости
   [`PlayerController.notSee()`](../../game-server/src/com/aionemu/gameserver/controllers/PlayerController.java#L132)
   отправляет [`SM_DELETE`](../../game-server/src/com/aionemu/gameserver/network/aion/serverpackets/SM_DELETE.java#L13).

Следствие: если тело AI — `Player`, поздно вошедший клиент автоматически
получит `SM_PLAYER_INFO` при своём `World.spawn()` и увидит актуальное движение,
поскольку пакет читает текущий `PlayerMoveController`. Специальный
«late join packet» не нужен. Если тело — `Npc`, клиент получит `SM_NPC_INFO`,
что не удовлетворяет спецификации.

### 3.4. Движение, heading, геоданные и pathfinding

Живой игрок:

- [`CM_MOVE.runImpl()`](../../game-server/src/com/aionemu/gameserver/network/aion/clientpackets/CM_MOVE.java#L77)
  разбирает movement mask, destination/vector, вызывает anti-hack, обновляет
  `World`, уведомляет controller и рассылает `SM_MOVE`.
- [`PlayerMoveController`](../../game-server/src/com/aionemu/gameserver/controllers/movement/PlayerMoveController.java#L15)
  хранит last client position, falling и movement state.
- [`PlayableMoveController.moveToDestination()`](../../game-server/src/com/aionemu/gameserver/controllers/movement/PlayableMoveController.java#L58)
  уже умеет серверную интерполяцию по скорости и обновляет позицию.
- [`PlayerMoveTaskManager`](../../game-server/src/com/aionemu/gameserver/taskmanager/tasks/PlayerMoveTaskManager.java#L12)
  обновляет всех зарегистрированных playable creatures каждые 200 ms одним
  общим task manager.
- [`SM_MOVE`](../../game-server/src/com/aionemu/gameserver/network/aion/serverpackets/SM_MOVE.java#L16)
  сериализует координаты, heading, mask и target/vector.

Однако
[`PlayableMoveController.startMovingToDestination()`](../../game-server/src/com/aionemu/gameserver/controllers/movement/PlayableMoveController.java#L37)
сейчас запускает серверное движение только при fear/confuse. Этапу 1 нужна
маленькая явная точка входа в `PlayerMoveController` для server-controlled
movement; она должна переиспользовать существующую интерполяцию и
`PlayerMoveTaskManager`, а не копировать `CM_MOVE`.

Полноценного поиска пути в репозитории нет. Это прямо отмечено в
[`SimpleAttackManager`](../../game-server/src/com/aionemu/gameserver/ai/manager/SimpleAttackManager.java#L75).
NPC movement выполняет движение к точке и локально корректирует Z/коллизии в
[`NpcMoveController.trySetValidGeoPoint()`](../../game-server/src/com/aionemu/gameserver/controllers/movement/NpcMoveController.java#L180).

Доступные примитивы:

- [`GeoService.getZ()`](../../game-server/src/com/aionemu/gameserver/world/geo/GeoService.java#L50);
- [`getClosestCollision()`](../../game-server/src/com/aionemu/gameserver/world/geo/GeoService.java#L124);
- [`findMovementCollision()`](../../game-server/src/com/aionemu/gameserver/world/geo/GeoService.java#L138);
- [`GeoService.canSee()`](../../game-server/src/com/aionemu/gameserver/world/geo/GeoService.java#L76).

Поэтому маршрут этапа 1 должен состоять из 3–5 заранее валидированных близких
точек на одной поверхности. Если прямой сегмент заблокирован, spawn должен
завершиться понятной ошибкой, а не телепортировать AI или пытаться выдать
локальную collision correction за pathfinding.

### 3.5. Бой, навыки, эффекты и cooldowns

| Операция | Входящий packet | Повторно используемая логика | Связь с packet |
|---|---|---|---|
| Auto attack | [`CM_ATTACK.runImpl()`](../../game-server/src/com/aionemu/gameserver/network/aion/clientpackets/CM_ATTACK.java#L45) | [`PlayerController.attackTarget()`](../../game-server/src/com/aionemu/gameserver/controllers/PlayerController.java#L395), [`PlayerRestrictions.canAttack()`](../../game-server/src/com/aionemu/gameserver/restrictions/PlayerRestrictions.java#L222) | Handler тонкий; controller можно вызвать доменно после выбора цели из perception. |
| Skill | [`CM_CASTSPELL.runImpl()`](../../game-server/src/com/aionemu/gameserver/network/aion/clientpackets/CM_CASTSPELL.java#L74) | [`PlayerController.useSkill()`](../../game-server/src/com/aionemu/gameserver/controllers/PlayerController.java#L457), [`SkillEngine.getSkillFor()`](../../game-server/src/com/aionemu/gameserver/skillengine/SkillEngine.java#L39), [`Skill.useSkill()`](../../game-server/src/com/aionemu/gameserver/skillengine/model/Skill.java#L237) | Timing/pet/cancel checks частично находятся в packet handler; перед боевым этапом нужен доменный command service. |
| Effects | — | [`PlayerEffectController`](../../game-server/src/com/aionemu/gameserver/controllers/effect/PlayerEffectController.java#L27), [`SkillEngine.applyEffectDirectly()`](../../game-server/src/com/aionemu/gameserver/skillengine/SkillEngine.java#L121) | Effect engine не требует клиентского ввода; direct apply допустим только когда это штатный результат skill/domain rule. |
| Skill cooldown | — | [`Skill.useSkill()`](../../game-server/src/com/aionemu/gameserver/skillengine/model/Skill.java#L297) | Штатный skill path записывает cooldown сам; AI не должен менять map cooldowns напрямую. |
| Item cooldown | [`CM_USE_ITEM`](../../game-server/src/com/aionemu/gameserver/network/aion/clientpackets/CM_USE_ITEM.java#L54) | `Player.hasCooldown/addItemCoolDown`, item actions | Полная оркестрация использования item находится в handler и требует выделения сервиса. |

Бой не входит в этап 1.

### 3.6. Квесты

- [`QuestService.checkStartConditions()`](../../game-server/src/com/aionemu/gameserver/services/QuestService.java#L278)
  проверяет доступность.
- [`QuestService.startQuest()`](../../game-server/src/com/aionemu/gameserver/services/QuestService.java#L400),
  [`finishQuest()`](../../game-server/src/com/aionemu/gameserver/services/QuestService.java#L77)
  и [`abandonQuest()`](../../game-server/src/com/aionemu/gameserver/services/QuestService.java#L849)
  являются основными доменными операциями.
- Quest hooks находятся в `QuestEngine` и вызываются из dialog, kill, zone,
  item и login flows.
- [`CM_DIALOG_SELECT.runImpl()`](../../game-server/src/com/aionemu/gameserver/network/aion/clientpackets/CM_DIALOG_SELECT.java#L57)
  содержит существенную orchestration: target resolution, interaction checks,
  dialog action и quest reporting.

AI не должен вызывать `runImpl()` пакета. До квестового этапа следует выделить
`PlayerQuestActions`, который принимает намерение `talk/start/finish`, заново
проверяет known-list, range, LoS и prerequisites и вызывает `QuestService` /
target controller.

### 3.7. Группы

[`PlayerGroupService`](../../game-server/src/com/aionemu/gameserver/model/team/group/PlayerGroupService.java#L30)
предоставляет `inviteToGroup`, `createGroup`, login/logout hooks и
`distributeKinah`. Packet
[`CM_INVITE_TO_GROUP`](../../game-server/src/com/aionemu/gameserver/network/aion/clientpackets/CM_INVITE_TO_GROUP.java#L22)
в основном разрешает имя и делегирует сервису.

Риск: `PlayerTeamMember.isOnline()` делегирует `Player.isOnline()`, а
group connect/disconnect packets и leadership опираются на этот признак.
Server-controlled presence надо отделить от network presence до этапа групп;
для этапа 1 группы исключены.

### 3.8. Инвентарь, экипировка и лут

- Предметы: [`ItemService.addItem()`](../../game-server/src/com/aionemu/gameserver/services/item/ItemService.java#L34).
- Перемещение: [`ItemMoveService.moveItem()`](../../game-server/src/com/aionemu/gameserver/services/item/ItemMoveService.java#L25).
- Экипировка: [`Equipment.equipItem()`](../../game-server/src/com/aionemu/gameserver/model/gameobjects/player/Equipment.java#L60)
  и [`unEquipItem()`](../../game-server/src/com/aionemu/gameserver/model/gameobjects/player/Equipment.java#L230).
- Лут: [`DropService.requestDropList()`](../../game-server/src/com/aionemu/gameserver/services/drop/DropService.java#L90)
  и [`requestDropItem()`](../../game-server/src/com/aionemu/gameserver/services/drop/DropService.java#L272).

`CM_LOOT_ITEM` тонко делегирует `DropService`, но item use целиком
оркестрируется в
[`CM_USE_ITEM.runImpl()`](../../game-server/src/com/aionemu/gameserver/network/aion/clientpackets/CM_USE_ITEM.java#L54),
а equip restriction находится в
[`CM_EQUIP_ITEM.runImpl()`](../../game-server/src/com/aionemu/gameserver/network/aion/clientpackets/CM_EQUIP_ITEM.java#L36).
Кроме того, `PlayerRestrictions.canUseItem()` требует `player.isOnline()`.

В будущих этапах AI должен получать item только через drop/quest/trade service,
а затем вызывать узкие `PlayerInventoryActions`. Прямое изменение storage или
таблиц поведениям AI запрещено.

### 3.9. Брокер и экономика

[`BrokerService`](../../game-server/src/com/aionemu/gameserver/services/BrokerService.java#L65)
имеет готовые операции:

- [`showRequestedItems()`](../../game-server/src/com/aionemu/gameserver/services/BrokerService.java#L98);
- [`buyBrokerItem()`](../../game-server/src/com/aionemu/gameserver/services/BrokerService.java#L244);
- [`registerItem()`](../../game-server/src/com/aionemu/gameserver/services/BrokerService.java#L361);
- [`settleAccount()`](../../game-server/src/com/aionemu/gameserver/services/BrokerService.java#L546).

Packet handlers дополнительно проверяют, что игрок реально targeting broker NPC:
[`CM_REGISTER_BROKER_ITEM`](../../game-server/src/com/aionemu/gameserver/network/aion/clientpackets/CM_REGISTER_BROKER_ITEM.java#L37)
и [`CM_BUY_BROKER_ITEM`](../../game-server/src/com/aionemu/gameserver/network/aion/clientpackets/CM_BUY_BROKER_ITEM.java#L33).
Эту проверку нельзя потерять в будущем `PlayerBrokerActions`. Экономика не
входит в этап 1.

### 3.10. Сохранение

[`PlayerService.storePlayer()`](../../game-server/src/com/aionemu/gameserver/services/player/PlayerService.java#L80)
координирует сохранение common data, skills, settings, quests, rank,
punishments, inventory, houses, mail, cooldowns и factions.
[`PlayerDAO.storePlayer()`](../../game-server/src/com/aionemu/gameserver/dao/PlayerDAO.java#L47)
пишет основную строку и координаты.

Этап 1 является transient spike: он загружает шаблон, но не вызывает
`storePlayer`, чтобы тестовое движение или runtime-префикс имени не попали в
БД. Постоянное состояние AI, отдельные таблицы метаданных и crash recovery
проектируются после приёмки видимости/движения.

### 3.11. Административные команды

Команды загружаются скриптовым
[`ChatProcessor`](../../game-server/src/com/aionemu/gameserver/utils/chathandlers/ChatProcessor.java#L24)
из каталогов `game-server/data/handlers/*`; access levels находятся в
`game-server/config/administration/commands.properties`.

В репозитории уже есть
[`//ai`](../../game-server/data/handlers/admincommands/Ai.java#L24) для
диагностики NPC AI. Этап 1 не должен менять смысл этой команды. Предлагается
отдельная временная команда `//aiplayer spawn|despawn|status`, чтобы исключить
конфликт. Публичные `.ai ...` из мастер-спецификации можно унифицировать после
spike отдельным решением.

### 3.12. Существующий NPC AI и планировщики

NPC AI состоит из:

- [`AIEngine`](../../game-server/src/com/aionemu/gameserver/ai/AIEngine.java#L28),
  загружающего script handlers;
- [`AbstractAI`](../../game-server/src/com/aionemu/gameserver/ai/AbstractAI.java#L31)
  с `AIState`, `AISubState` и event dispatch;
- [`NpcAI`](../../game-server/src/com/aionemu/gameserver/ai/NpcAI.java#L33);
- [`ThinkEventHandler`](../../game-server/src/com/aionemu/gameserver/ai/handler/ThinkEventHandler.java#L20);
- `WalkManager`, attack managers и `NpcMoveController`;
- общих [`MoveTaskManager`](../../game-server/src/com/aionemu/gameserver/taskmanager/tasks/MoveTaskManager.java#L16)
  и `ThreadPoolManager`.

У каждого `Creature` поле AI создаётся в конструкторе и является `final`:
[`Creature`](../../game-server/src/com/aionemu/gameserver/model/gameobjects/Creature.java#L43).
Обычный `PlayerCommonData.getAiName()` возвращает `null`, поэтому игрок
получает dummy AI. Существующий `//ai set` меняет final field reflection и не
является подходящей продуктовой точкой расширения.

Полезны идеи event/state machine, geo checks и общих task managers. Нельзя
переиспользовать `Npc` как тело игрока: тип объекта, packet presentation,
skills, stats, inventory, quests и groups различаются.

## 4. Что действительно требует AionConnection

### 4.1. Не требует соединения

- Конструирование и полная загрузка `Player`.
- `World.storeObject`, `spawn`, known-list, map regions и `removeObject`.
- Сериализуемые данные `SM_PLAYER_INFO`: соединение нужно наблюдателю, а не
  представляемому игроку.
- Рассылка движения/удаления наблюдателям.
- Большая часть stat, effect, skill, quest, inventory и broker domain model,
  если вызывающий слой уже прошёл корректные проверки.

### 4.2. Требует или неявно предполагает соединение

1. `Player.isOnline()` возвращает `clientConnection != null`.
2. `PlayerRestrictions.canTrade`, `canUseItem`, `canChat` отвергают игрока без
   connection.
3. Group member online state, loot distribution, summon tasks, aura tasks и
   interaction tasks используют `isOnline`.
4. `MultiClientingService`, PvP anti-abuse, AutoBan, AntiHack, Punishment и
   invalid-region branch в `World.updatePosition()` напрямую читают IP/MAC или
   закрывают connection.
5. `PlayerLeaveWorldService` в конце безусловно обращается к сохранённому
   `AionConnection`.
6. [`DebugService`](../../game-server/src/com/aionemu/gameserver/services/DebugService.java#L30)
   каждые 30 минут считает любого world player без connection аномалией.
7. Login-only сервисы и self packets ожидают active client state.

Создавать fake `AionConnection` опасно: его конструктор требует
`SocketChannel`/`Dispatcher`, создаёт crypto/queues, запускает alive checker и
участвует в disconnect/logout. Заглушка стала бы ложной сетевой сессией,
скрыла бы неправильную семантику `isOnline` и добавила бы фоновые tasks.

## 5. Граница общей сущности и ролевых контроллеров

```text
ServerControlledPlayer
  owns -> Player                       # единственное тело в World
  owns -> ServerPlayerController       # route либо companion behavior
  owns -> runtime state                # role и lifecycle

CompanionController
  reads -> CompanionContext            # owner/body snapshot
  uses -> PlayerActionGateway          # только разрешённые игровые действия

PlayerActionGateway (этап 2A)
  -> checkMovement                     # region + collision + LoS
  -> startMove
  -> stopMove

ServerPlayerController
  <- RouteServerPlayerController       # технический маршрут этапа 1
  <- CompanionController               # follow/stay/blocked этапа 2A
  <- CitizenController                 # будущие автономные цели
```

`ServerControlledPlayer` отвечает за общее тело, role и lifecycle. Ролевые
контроллеры принимают решения, но игровые действия выполняют только через
узкие gateway-интерфейсы. Методы следующих доменов не добавляются заранее.

В этапе 1 используется `RouteServerPlayerController`. В этапе 2A реализован
`CompanionController`, который принимает только решения follow/stay/blocked и
не обращается к `World`, movement controller, geodata или packet-коду напрямую.
Его `PlayerActionGateway` содержит только `checkMovement`, `startMove` и
`stopMove`. Combat, skills, quests, groups, inventory и economy не подключены.

## 6. Общий scheduler и отсутствие фоновой симуляции

Реализован один общий `SyntheticPlayerScheduler`, запущенный через существующий
`ThreadPoolManager`, а не поток на AI.

- Registry хранит активные wrappers по player object ID и отдельно ограничивает
  единственность каждой role.
- Очередь этапов 1/2A обходится round-robin.
- На каждый scheduler run задаются `maxActions` и `budgetNanos`.
- После исчерпания бюджета остаток остаётся на следующий run.
- Movement интерполируется существующим `PlayerMoveTaskManager` раз в 200 ms.
- High-level controller ticks выполняются реже и распределяются по фазам.
- Длительные операции не выполняются внутри общего lock.
- Tick никогда не «догоняет» пропущенное офлайн-время.

Жёсткий eligibility predicate:

```text
registry contains wrapper
&& wrapper.state == ACTIVE
&& World.findVisibleObject(id) == player
&& player.isInWorld()
&& player.isSpawned()
```

Если он ложен, AI снимается с очереди без прогресса. После despawn или
перезапуска нет начисления расстояния, XP, item или kinah за прошедшее время.

## 7. Восприятие без всеведения

`KnownList` ограничивает кандидатов расстоянием и map regions, но сам по себе
не проверяет геометрический line of sight. Поэтому perception snapshot должен
включать объект только если одновременно:

1. объект уже находится в `player.getKnownList()`;
2. `knownList.sees(object)` истинно;
3. `player.canSee(object)` пропускает stealth/visual-state;
4. объект находится в лимите конкретного действия;
5. `GeoService.canSee(player, object)` подтверждает LoS;
6. map ID и instance ID совпадают.

Контроллеру запрещены `World.getAllPlayers()`, глобальные DAO queries,
чужие inventory/quests и будущие market changes. Он получает immutable
snapshot. Память может хранить только ранее наблюдавшиеся факты с timestamp и
должна помечать их stale.

## 8. Feature flags

Все новые значения по умолчанию `false`:

```properties
ai.enabled=false
ai.synthetic_players.enabled=false
ai.companions.enabled=false
ai.citizens.enabled=false
ai.economy.enabled=false
```

Этап 1 читает только первые два флага; остальные резервируются и не включают
код. Эффективное условие — логическое AND global и feature flag.
Отключение `ai.enabled` должно:

1. запретить новые spawn;
2. остановить выдачу новых AI actions;
3. безопасно удалить уже активные synthetic players;
4. не менять обычных players/NPC/quests/broker.

## 9. Наблюдаемость и профиль 0/10/20

В проекте уже есть
[`RunnableStatsManager`](../../commons/src/com/aionemu/commons/utils/concurrent/RunnableStatsManager.java#L16),
но он хранит count/total/min/max и не даёт p50/p95/p99. Для AI нужен отдельный
малый metrics collector.

Минимальные runtime metrics:

- active/paused/removing AI;
- scheduler run duration и action duration: p50/p95/p99/max;
- queue size, deferred by budget, failures;
- movement updates, stuck/replan count;
- Geo `getZ/collision` и LoS call count/duration;
- `SM_PLAYER_INFO`, `SM_MOVE`, `SM_DELETE` count и оценка bytes для AI;
- heap used, GC pause, live threads;
- game/player packet executor queue и общий tick/task delay.

Сравнение 0/10/20 проводится не на этапе 1, а до допуска массового spawn:

1. Одинаковая JVM, configs, map, два наблюдателя и 30-минутный сценарий.
2. Отдельный restart и 10 минут warm-up перед каждым профилем.
3. Профили последовательно: `0 -> 10 -> 20 -> 0`, минимум три повтора.
4. Снять Java Flight Recorder, `jcmd GC.heap_info`, GC logs, process CPU/RSS и
   сетевой traffic.
5. Снять AI histograms, path/LoS calls, packet counts и scheduler budget
   misses.
6. Сравнивать median и p95 по steady-state, а также проверить возврат к
   baseline после `20 -> 0`.
7. Бюджет деградации утвердить после первого baseline на целевом сервере;
   заранее скрывать регрессию произвольным порогом нельзя.

## 10. Риски и открытые вопросы

| Риск/вопрос | Последствие | Мера |
|---|---|---|
| `isOnline == has connection` | Items, interactions, groups, loot и часть effects считают AI offline | Не подделывать connection; перед соответствующим этапом разделить network/world presence. |
| Обычные enter/leave services сетевые | NPE, login side effects, неправильное сохранение | Отдельный idempotent lifecycle service. |
| `DebugService` считает AI аномалией | Warning каждые 30 минут | Явно распознавать registry-controlled players. |
| Нет pathfinding | AI застрянет у препятствия | Этап 1 — только заранее валидированный прямой маршрут; полноценный pathfinder — отдельное решение. |
| `SM_PLAYER_INFO` требует полноты модели | NPE на settings/rank/motions/account | Загружать через `PlayerService.getPlayer`, добавить preflight validation. |
| Template character может войти настоящим клиентом | Duplicate object ID / конфликт аккаунта | Отдельный закрытый account, duplicate guard, никогда не выдавать credentials. |
| Runtime-префикс `[AI]` не проходит обычную name validation | Несогласованность DB/display | В spike менять только runtime copy до `World.storeObject`; вручную подтвердить отображение клиентом. |
| `World.getAllPlayers()` включает AI | Online counters и сервисы могут считать AI живым клиентом | Ввести явную классификацию и аудит consumers до массового этапа. |
| Invalid map region branch закрывает connection | NPE у AI | Валидировать route/region до старта; отдельно сделать branch transport-safe до дальних маршрутов. |
| NPC movement mask на Player-клиенте | Возможна неправильная анимация | Проверить `0xE0`/stop sequence двумя клиентами в spike, не считать компиляцию доказательством. |
| Registry/wrapper и `World` расходятся | Ghost, duplicate, retained reference | Lifecycle state machine, idempotent remove, postconditions и metrics. |
| Stage 1 transient state | Нет recovery после restart | Ожидаемо для spike; persistence не добавлять преждевременно. |

## 11. Критерии безопасного удаления

Удаление считается завершённым только если:

1. wrapper атомарно перешёл `ACTIVE -> REMOVING`; повторный вызов безопасен;
2. controller больше не выдаёт intents;
3. scheduler и `PlayerMoveTaskManager` больше не содержат игрока;
4. движение остановлено и target очищен;
5. `World.removeObject(player)` выполнен ровно один раз;
6. двусторонние known-lists очищены, наблюдатели получили `SM_DELETE`;
7. `World.findVisibleObject(id) == null` и `player.isSpawned() == false`;
8. registry не держит wrapper/player;
9. в этапе 1 не вызваны login logout hooks и не сохранена transient позиция;
10. ошибки cleanup логируются, а `finally` всё равно удаляет registry reference.

## 12. План отката

Операционный rollback:

1. выставить `ai.enabled=false` и `ai.synthetic_players.enabled=false`;
2. выполнить штатный `despawn all`/graceful shutdown;
3. убедиться, что registry пуст и AI object IDs отсутствуют в `World`;
4. перезапустить game server;
5. проверить вход двух обычных клиентов, known-list, NPC и broker.

Кодовый rollback этапа 1 ограничивается новыми AI classes/command/tests,
двумя config entries и узкой movement entry point. В spike нет schema
migration и нет записи AI progression в БД, поэтому data rollback не нужен.
