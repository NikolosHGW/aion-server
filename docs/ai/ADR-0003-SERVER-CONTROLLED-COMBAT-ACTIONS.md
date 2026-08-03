# ADR-0003: Server-controlled combat и owner contribution attribution

Статус: Accepted

Дата: 2026-08-02

Дата принятия: 2026-08-03

## Контекст

По принятому [ADR-0001](ADR-0001-SERVER-CONTROLLED-PLAYER.md) единственное
тело AI в `World` — полностью загруженный `Player` без `AionConnection`, а
действия выполняются через узкие gateways. Этап 2A использует
`PlayerActionGateway` для движения; этап 2B только читает quest data.

Первый combat slice должен дать персональному companion один настоящий basic
hit в ответ на один настоящий `ObserverType.ATTACK` event владельца. Физическим
атакующим остаётся `[AI]`-`Player`: его оружие и stats определяют hit, он
соблюдает range/LoS/geodata, создаёт штатную animation, попадает в threat и
может получить ответный damage. При этом его вклад в штатную награду encounter
семантически принадлежит владельцу, а не образует вторую экономическую
сущность.

Штатное ядро уже разделяет эти понятия:

- [`NpcController.onAttack()`](../../game-server/src/com/aionemu/gameserver/controllers/NpcController.java)
  сохраняет spawned `Summon` как физический источник damage/threat;
- [`AggroList.addDamage()`](../../game-server/src/com/aionemu/gameserver/controllers/attack/AggroList.java)
  хранит raw attacker;
- [`DamageList`](../../game-server/src/com/aionemu/gameserver/controllers/attack/DamageList.java)
  проецирует raw attacker на `attacker.getMaster()` и суммирует contribution;
- [`TeamDamageList`](../../game-server/src/com/aionemu/gameserver/controllers/attack/TeamDamageList.java)
  затем проецирует `Player`-master на его текущую team;
- [`NpcController.doReward()`](../../game-server/src/com/aionemu/gameserver/controllers/NpcController.java)
  и
  [`PlayerTeamDistributionService.doReward()`](../../game-server/src/com/aionemu/gameserver/model/team/common/service/PlayerTeamDistributionService.java)
  применяют обычные solo/group quest, XP/DP/AP и loot rules.

`Player.getMaster()` для обычного Player возвращает его самого. Переопределять
эту глобальную семантику для runtime companion нельзя: `getMaster()` и
`getActingCreature()` используются также hostility, duel, visibility, PvP и
target selection. Нужна узкая проекция только на contribution boundary.

Отдельная граница — смерть полностью загруженного synthetic `Player`:
[`PlayerController.onDie()`](../../game-server/src/com/aionemu/gameserver/controllers/PlayerController.java)
запускает resurrection, instance/zone/PvP hooks, EXP loss и quest death hooks
даже без connection. Обычный despawn после смерти эти последствия не отменяет.

## Принятое решение

Все девять implementation decisions подтверждены владельцем проекта
2026-08-02. Код, автоматические проверки и минимальный client-visible combat
flow вручную приняты владельцем проекта 2026-08-03.

1. Создать отдельный `CombatActionGateway`; не расширять movement-oriented
   `PlayerActionGateway` combat/quest/inventory методами.
2. Первый action — один штатный basic hit с `time=0` на один уникальный
   доказанный owner `ObserverType.ATTACK` event. Не создавать attack loop,
   отдельный scheduler, thread или delayed hit.
3. `//companion assist on` только вооружает режим. Selection и команда не
   являются trigger. Event несёт exact target identity и короткий TTL.
4. Target — только allowlisted ordinary open-world hostile `Npc`: для spike
   exact template `210115` и исходный spawn `1234.05,1042.47,144.726`, heading
   `33`; exact world identity, та же map/instance, KnownList и visibility у owner и
   companion, alive/vulnerable, range и LoS/geodata. PvP, boss, elite, event,
   siege и instance targets запрещены.
5. Gateway вызывает checked player basic-attack path
   `PlayerController.attackTarget(target, 0, false)`. Запрещены `CM_ATTACK`,
   fake connection, direct `AttackUtil`, direct HP mutation, admin damage и
   animation-only packet broadcast.
6. Raw companion остаётся physical attacker в `AggroList` и threat. Узкий
   `CombatContributionOwnerResolver` применяется при построении final reward
   contribution и при переносе damage удаляемого attacker в
   `AggroList.transferDamagesToMaster()`.
7. Resolver возвращает owner только если attacker — exact body зарегистрированной
   companion session и роль exact `SyntheticPlayerRole.COMPANION` (продуктовая
   семантика `PERSONAL_COMPANION`). Для нового hit mapping обязан быть `ACTIVE`;
   во время удаления он кратко остаётся `REMOVING`, чтобы KnownList cleanup
   перенёс уже нанесённый damage, и unregister-ится последним. Во всех остальных
   случаях resolver возвращает существующий `attacker.getMaster()`.
8. Owner и companion damage суммируются под одним owner key ровно один раз.
   Если owner в группе, существующий `TeamDamageList` и
   `PlayerTeamDistributionService` применяют обычную group semantics. Companion
   не вступает в группу и не занимает slot.
9. Resolver не является глобальным правилом synthetic players. Будущий
   `CITIZEN_AI` всегда остаётся самостоятельным contributor. PvP в 2C
   запрещён gateway и должен fail closed до damage; PvP attribution этим ADR
   не разрешается.
10. Quest `onAttack`/`onAddAggroList` hooks для physical PERSONAL_COMPANION
    подавляются, а не перенаправляются owner: owner уже породил собственный
    attack event, поэтому перенаправление могло бы дать двойной attack-stage
    progress. Kill credit возникает один раз в обычном NPC reward path после
    owner projection.
11. Ввести отдельный ранний synthetic death policy: остановить decisions,
    movement/target/combat, показать допустимый общий death/despawn state и
    identity-safe удалить runtime body до resurrection, PvP, EXP loss, quest,
    reward и persistence hooks.
12. Combat template обязан иметь plain weapon без active/equipped power shards
    и godstone. Runtime template не сохраняется через
    `PlayerService.storePlayer()` и не получает periodic save tasks.
13. После reward resolution в будущем можно добавить read-only событие, например
    `CompanionEncounterResolvedEvent`: companion/owner/target identities, raw
    companion damage, participation/result и итог native owner/group reward.
    Будущий `CompanionProgressionService` использует отдельный companion
    profile и persistence; событие не выдаёт второй native XP, quest credit,
    drop, loot или kinah. В 2C событие и progression service не реализованы.
14. Все combat flags default false. Disable/dismiss/logout/map change/death/
    shutdown detach-ят observer, останавливают combat/movement и очищают
    runtime references без DB save.

## Почему штатный master precedent применим

[`Creature.getMaster()`](../../game-server/src/com/aionemu/gameserver/model/gameobjects/Creature.java)
по умолчанию возвращает self. [`Summon`](../../game-server/src/com/aionemu/gameserver/model/gameobjects/Summon.java)
возвращает owning `Player`; [`SummonedObject`](../../game-server/src/com/aionemu/gameserver/model/gameobjects/SummonedObject.java)
(servant/trap/homing object) возвращает creator-`Creature`. Поэтому raw damage
может иметь отдельную физическую identity, а final reward list объединяет его с
master. `DamageList.computeIfAbsent(master).addDamage(...)` исключает двойную
строку; следующий team projection использует именно team owner.

Для companion нельзя просто менять `Player.getMaster()`. Resolver повторяет
существующий projection rule в более узком контексте и сохраняет raw companion
в threat. Он также нужен в `AggroList.remove()`: иначе dismiss/выход companion
из KnownList до смерти NPC удалит его raw damage, поскольку обычный Player
сейчас является собственным master. Mapping должен быть доступен до unregister
session/registry; cleanup обязан сохранить этот порядок.

## Рассмотренные варианты

### A. No-reward quarantine всего encounter

Плюсы: легко доказать отсутствие native reward от synthetic contribution;
пригоден как аварийный fallback для диагностического стенда.

Минусы: один companion hit лишает XP/quest/drop также owner и сторонних
игроков; griefing surface; персональный помощник ухудшает обычный gameplay;
нельзя принять как продуктовую экономическую модель.

Решение: отклонён как основной вариант; не включать в минимальную реализацию.

### B. Настоящее group membership companion

Плюсы: использует готовую group reward distribution.

Минусы: занимает slot и появляется в UI; connection-dependent invite/leave/
disconnect lifecycle; companion получает собственную долю и может уменьшить
долю owner; создаёт двойное участие owner+companion и persistence/loot risks.

Решение: отклонён.

### C. Master/owner contribution attribution

Плюсы: соответствует существующей summon/master архитектуре; сохраняет
physical damage/threat; объединяет owner+companion ровно один раз; естественно
переходит в team owner; не меняет encounters без active companion mapping.

Минусы: нужен central, но узкий resolver в `DamageList` и transfer-on-remove;
нужны role/session identity tests, quest-hook guard и проверка всех reward
hooks; lifecycle mapping нельзя удалить раньше KnownList cleanup.

Решение: предпочтительный кандидат.

### D. Отдельный companion reward pipeline

Плюсы: полная свобода будущей companion progression.

Минусы: дублирует NPC/group/quest/drop/event rules, легко выдаёт двойную
награду, расходится с новыми server mechanics и требует постоянного
сопровождения второго reward engine.

Решение: отклонён для native rewards. Отдельный progression service допустим
только как consumer итогового encounter event без повторной native награды.

## Результаты ручной приёмки и принятое ограничение

Ручная проверка в реальном клиенте 2026-08-03 подтвердила: assist default off;
attach по `//companion assist on`; trigger только от owner attack; fail-closed
`TARGET_NOT_ALLOWED` для NPC `210663`; успешный checked hit только по
allowlisted NPC `210115`; heading, экипированную Training Mace, weapon mode и
настоящую basic weapon animation; ровно один damage и physical aggro companion
без delayed/repeated hit. `//companion assist off` detach-ит observer, очищает
pending event, возвращает neutral mode и визуально убирает оружие. Fake
connection, `CM_ATTACK` simulation и direct HP damage отсутствуют; регрессий
follow/stay и presentation не обнаружено.

Owner contribution attribution архитектурно принят: raw attacker/threat —
companion, final native contribution — exact owner через role/session-aware
resolver, без второй companion reward row.

Natural death companion вручную **не проверена**. NPC `210115` наносит меньше
damage, чем пассивная регенерация fixture `Cool`; владелец не использовал
`//damage`, не менял параметры/DB-state fixture и не расширял allowlist.
Synthetic early-death design и cleanup сохраняются и покрыты автоматическими
тестами, но client-visible death flow является deferred acceptance item. Его
нужно повторно проверить до более сильных NPC, сложных квестов, instances или
production rollout. Это принятое ограничение не блокирует минимальный 2C.

## Последствия и риски

Положительные:

- настоящий player combat без fake client/connection и protocol changes;
- обычный reward path и group semantics остаются источником истины;
- companion не становится независимым участником экономики;
- ordinary encounters и `CITIZEN_AI` сохраняют исходное поведение;
- future progression имеет отдельный seam без двойного drop/quest/XP.

Отрицательные и открытые риски:

- `DamageList` используется также [`PvpService`](../../game-server/src/com/aionemu/gameserver/services/PvpService.java);
  PvP должен быть запрещён до hit и покрыт regression tests, а расширение
  resolver на PvP требует отдельного ADR;
- target death проходит instance/event hooks рядом с reward path; allowlist
  обязан исключать scripted/event/instance/siege NPC;
- raw contributor может исчезнуть до target death; transfer-on-remove и
  cleanup ordering обязательны;
- `PlayerController.attackTarget()` возвращает `void`, поэтому gateway нужны
  fail-closed preflight и attack-counter postcondition;
- power shard depletion может немедленно вызвать `InventoryDAO.store()`;
  godstone/effects/skills и delayed attacks остаются вне slice;
- synthetic death требует узкой branch в центральном controller и особенно
  строгих regression/source-boundary tests.

## Подтверждение границ и статус

Владелец проекта подтвердил:

1. вариант C и узкий role/session-aware `CombatContributionOwnerResolver`;
2. сохранение companion как physical threat source при owner reward mapping;
3. transfer contribution owner при dismiss до NPC death;
4. suppression companion attack-stage quest hooks без их перенаправления;
5. synthetic early death branch;
6. allowlist только NPC `210115` (Juvenile Sparkie) для первого spike;
7. exact one companion basic hit per owner ATTACK event и assist default off;
8. точечные integration changes из
   [IMPLEMENTATION_PLAN_2C](IMPLEMENTATION_PLAN_2C.md).

Реализация использует отдельные `CombatActionGateway`,
`OwnerAttackSignalSource`, `CompanionCombatController` и
`CombatContributionOwnerResolver`. Gateway вызывает
`PlayerController.attackTarget(target, 0, false)`; physical attacker остаётся
companion, а `DamageList` и transfer-on-remove проецируют contribution на exact
owner. Quest attack-stage hooks подавлены только для зарегистрированного
personal companion; ранняя death branch изолирована exact active session.

Cleanup переводит mapping в `REMOVING`, detach-ит observer, останавливает
combat/movement, удаляет body через World/KnownList (где происходит transfer),
а attribution unregister выполняет последним. Отдельного scheduler/thread,
fake connection, client packet simulation, direct HP, `Player.getMaster()`
change или persistence нет.

ADR не разрешает skills, autonomous combat, PvP, bosses/elites/events/siege/
instances, quests execution, real group membership, looting/economy,
companion progression/persistence или `CITIZEN_AI`. Эти границы не считаются
принятыми или проверенными функциональными возможностями этапа 2C.
