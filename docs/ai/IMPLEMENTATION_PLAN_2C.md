# Этап 2C: минимальная помощь PERSONAL_COMPANION в бою

Статус: принят вручную владельцем проекта 2026-08-03

Дата повторного аудита: 2026-08-02

Baseline: branch `feature/ai-mvp`, commit
`6f354a9a9767d45772c94b4d2f8c78b32d5ddfc7`.

Зависимости:

- [ADR-0001](ADR-0001-SERVER-CONTROLLED-PLAYER.md): единственное тело AI в
  `World` — fully loaded `Player` без `AionConnection`;
- [этап 2A](IMPLEMENTATION_PLAN_2A.md): приняты follow/stay/blocked, lifecycle и
  общий `SyntheticPlayerScheduler`;
- [ADR-0002](ADR-0002-READ-ONLY-QUEST-GOAL-PLANNING.md) и
  [этап 2B](IMPLEMENTATION_PLAN_2B.md): принят read-only quest planning, но не
  quest execution;
- предлагаемое combat-решение —
  [ADR-0003](ADR-0003-SERVER-CONTROLLED-COMBAT-ACTIONS.md), статус `Accepted`.

## 0. Рекомендация и границы

Аудит подтвердил вариант C: raw companion должен остаться физическим
атакующим, а его final PvE reward contribution проецируется на exact owner по
штатному summon/master precedent. Damage owner и companion суммируется один
раз; если owner в team, дальше работает существующее team distribution.

Минимальный 2C после отдельного подтверждения:

- `//companion assist on|off|status`, default off;
- одно подтверждённое owner `ObserverType.ATTACK` событие даёт не более одного
  companion basic hit по exact target;
- target allowlist содержит только NPC `210115` для первого spike;
- checked server player attack, real weapon/stats/animation/damage/threat;
- owner contribution mapping без независимой reward-строки companion;
- synthetic early death cleanup;
- ни skills, ни auto-attack loop, ни самостоятельный target selection;
- ни PvP, boss/elite/event/siege/instance combat, quest execution, loot
  decisions, real group membership, economy или persistence.

No-reward quarantine остаётся только отклонённым fallback: он слишком жёсток,
потому что лишает reward owner и сторонних игроков после одного companion hit.

Все девять решений раздела 20 подтверждены владельцем проекта 2026-08-02.
Implementation preflight не обнаружил необходимости в fake connection, packet
simulation, direct HP, сохранении runtime template, широком rewrite reward
subsystem или изменении `Player.getMaster()`.

Первая ручная проверка двумя клиентами 2026-08-03 подтвердила trigger,
checked damage и physical aggro на exact NPC `210115`, но не принята: companion
резко повернулся к target, а weapon attack animation у owner и observer не
появилась. HP NPC визуально изменился без удара; на тот момент ADR-0003
оставался `Proposed`.

После исправления target/weapon-mode presentation path повторная проверка в
реальном клиенте 2026-08-03 успешно подтвердила настоящий basic weapon swing,
один damage и physical aggro. Владелец проекта принял минимальный этап 2C и
перевёл ADR-0003 в `Accepted`. Natural/synthetic death вручную не проверялась и
остаётся отдельным deferred acceptance item; это ограничение не блокирует
принятие текущего узкого allowlisted spike.

## 1. Доказанный basic-attack call chain

| Шаг | Файл/метод | Фактическая семантика |
|---|---|---|
| Client input живого игрока | [`CM_ATTACK.runImpl()`](../../game-server/src/com/aionemu/gameserver/network/aion/clientpackets/CM_ATTACK.java) | Получает active player из connection, resolve target через KnownList и вызывает controller. Для companion пакет и connection не нужны и запрещены. |
| Checked player path | [`PlayerController.attackTarget()`](../../game-server/src/com/aionemu/gameserver/controllers/PlayerController.java) | `PlayerRestrictions.canAttack`, weapon range, distance, `GeoService.canSee`, attack-speed check, затем superclass. Возвращает `void`; client cadence имеет `+300 ms` ping tolerance, поэтому gateway делает строгий server cadence. |
| Calculation/broadcast | [`CreatureController.attackTarget()`](../../game-server/src/com/aionemu/gameserver/controllers/CreatureController.java) | Использует actual equipment/stats, `AttackUtil`, attack counter и один `SM_ATTACK`; packet содержит attacker/target, time, weapon attack type/hand и actual results. Broadcast виден clients без connection у attacker. Companion сохраняет `time=0` и ровно один immediate damage. |
| Owner trigger | там же, `notifyAttackObservers(target, 0)` | `ObserverType.ATTACK` после настоящей basic action несёт exact target и skill ID `0`. Selection/команда/combat flag этого не доказывают. |
| Target controller | [`NpcController.onAttack()`](../../game-server/src/com/aionemu/gameserver/controllers/NpcController.java) -> `CreatureController.onAttack()` | Применяет damage, записывает damage/hate и уменьшает HP. Spawned summon специально остаётся physical `actingCreature`; обычный companion-Player также остаётся raw attacker. |
| Raw contribution | [`AggroList.addDamage()`](../../game-server/src/com/aionemu/gameserver/controllers/attack/AggroList.java) | `AggroInfo` keyed raw attacker object ID; physical threat/target selection продолжает видеть companion. |
| Master projection | [`AggroList.getFinalDamageList()`](../../game-server/src/com/aionemu/gameserver/controllers/attack/AggroList.java) -> [`DamageList`](../../game-server/src/com/aionemu/gameserver/controllers/attack/DamageList.java) | Сейчас каждую raw строку проецирует на `attacker.getMaster()` и суммирует. Новый resolver меняет только projection exact active PERSONAL_COMPANION на owner. |
| Team projection | [`DamageList.toTeamDamages()`](../../game-server/src/com/aionemu/gameserver/controllers/attack/DamageList.java) -> [`TeamDamageList`](../../game-server/src/com/aionemu/gameserver/controllers/attack/TeamDamageList.java) | Player contribution отображается на `player.getCurrentTeam()`; owner group получает combined contribution без group slot companion. |
| Reward | [`NpcController.doReward()`](../../game-server/src/com/aionemu/gameserver/controllers/NpcController.java) | Выбирает winner/share. Solo path вызывает quest kill, event PvE, XP/DP/AP и drop registration. |
| Group reward | [`PlayerTeamDistributionService.doReward()`](../../game-server/src/com/aionemu/gameserver/model/team/common/service/PlayerTeamDistributionService.java) | Фильтрует online/in-range members, выдаёт quest/XP/DP/AP и loot rights штатным участникам team owner. |
| Death hooks | `NpcController.onDie()` | `doReward()` вызывается до общего death/respawn; instance handler `onDie` остаётся. Поэтому allowlist исключает custom/instance/event/siege target. |

Итоговый поток:

```text
owner real ATTACK event
  -> CombatActionGateway checked basic hit by companion Player
  -> NpcController / CreatureController real damage
  -> AggroList raw key = companion (physical damage + threat)
  -> CombatContributionOwnerResolver: companion -> exact owner
  -> DamageList owner row (owner damage + companion damage, once)
  -> TeamDamageList owner.getCurrentTeam(), if any
  -> existing NpcController / PlayerTeamDistributionService rewards
```

Ordinary player/summon/servant/trap/NPC encounters получают default resolver
result `attacker.getMaster()` и не меняют семантику.

## 2. Master/owner attribution audit

1. **Готовое понятие есть.**
   [`Creature.getMaster()`](../../game-server/src/com/aionemu/gameserver/model/gameobjects/Creature.java)
   default self; `getActingCreature()` default master.
2. **Summon.**
   [`Summon.getMaster()`](../../game-server/src/com/aionemu/gameserver/model/gameobjects/Summon.java)
   возвращает owner-`Player`. `NpcController.onAttack()` намеренно сохраняет
   spawned summon как raw damage/threat source, а `DamageList` проецирует reward
   на owner.
3. **Servant/trap/homing object.**
   [`SummonedObject.getMaster()`](../../game-server/src/com/aionemu/gameserver/model/gameobjects/SummonedObject.java)
   возвращает creator-`Creature`; non-Summon может быть отображён на acting
   master уже при `NpcController.onAttack()`. `AggroList.addHate()` также имеет
   master rule для summoned objects, кроме taunting spirit.
4. **Pet.** Обычный [`Pet`](../../game-server/src/com/aionemu/gameserver/model/gameobjects/Pet.java)
   не является combat `Creature`; его master semantics используется, например,
   autoloot в `NpcController`, но не является damage precedent.
5. **Группа master.** `DamageList` сначала объединяет raw damage под master,
   затем `TeamDamageList` отображает master-Player на его team. Companion не
   получает отдельную share.
6. **Двойной вклад.** `computeIfAbsent(owner).addDamage()` суммирует owner и
   companion в одной строке. Нельзя дополнительно создавать companion row,
   вручную добавлять owner damage или второй reward pipeline.
7. **Уход до смерти NPC.** [`AggroList.remove()`](../../game-server/src/com/aionemu/gameserver/controllers/attack/AggroList.java)
   переносит raw damage на `getMaster()`. Для обычного Player это self, поэтому
   реализация использует тот же role-aware resolver в
   `transferDamagesToMaster()` и сохранять session mapping до KnownList/world
   cleanup.
8. **Player death/PvP.** [`PvpService`](../../game-server/src/com/aionemu/gameserver/services/PvpService.java)
   тоже читает `DamageList`. 2C запрещает `Player` targets и PvP до hit;
   attribution PvP этим планом не разрешается и требует отдельного решения.
9. **Quest hooks.** `NpcController.onAttack()` и `onAddHate()` увидят companion
   как `Player`. Для PERSONAL_COMPANION их надо подавить. Перенаправлять owner
   нельзя: owner уже атаковал и может получить двойной attack-stage event.
10. **Connection.** Checked server call не требует connection. Connection нужна
    только `CM_ATTACK`; self feedback через `PacketSendUtility` для body с null
    connection безопасно не доставляется, а broadcast observers получают.

### 2.1. Узкий аудит presentation path и первопричина

Обычный connected player:

```text
CM_ATTACK.readImpl(): time = readUH()
  -> CM_ATTACK.runImpl(): connection.activePlayer + KnownList target
  -> PlayerController.attackTarget(target, time, false)
  -> checked restrictions/range/LoS/cadence
  -> CreatureController.attackTarget(target, presentationTime=time,
     damageDelay=time, true)
  -> one AttackUtil calculation
  -> one SM_ATTACK(... presentationTime=time, type, hand, results ...)
  -> self delivery through AionConnection + same packet to every Player in
     attacker KnownList
  -> damage now when time=0, otherwise one DelayedOnAttack after that time
```

Companion до исправления:

```text
DefaultCombatActionGateway successful preflight
  -> PlayerController.attackTarget(target, 0, false)
  -> CreatureController
  -> one SM_ATTACK(... time=0, MELEE/RANGED, MAIN_HAND/OFF_HAND, results ...)
  -> owner and observer receive the packet through companion KnownList
  -> immediate real damage once
```

Packet не отсутствовал, и null `AionConnection` не блокировал observer
broadcast. Первая коррекция заменила packet time `0` на current attack speed,
сохранив immediate damage. Повторная ручная проверка 2026-08-03 снова показала
damage без swing animation, поэтому timing-гипотеза опровергнута, а изменение
центрального `CreatureController` полностью удалено.

Повторный source audit доказал более ранние обязательные шаги normal Player:

```text
CM_TARGET_SELECT -> Player.setTarget -> SM_TARGET_UPDATE observers
CM_EMOTION(ATTACKMODE_IN_STANDING)
  -> CreatureState.WEAPON_EQUIPPED
  -> SM_EMOTION(ATTACKMODE_IN_STANDING) observers
CM_ATTACK -> checked PlayerController -> SM_ATTACK
```

Server-controlled companion выполнял только последний шаг. Клиенты знали body,
но продолжали держать его в peace mode без опубликованного target/weapon state;
это согласуется с повторным наблюдением damage «от взгляда».

Вторая коррекция зеркалирует только доказанные normal prerequisites:

- после gateway preflight и всех checked `PlayerController` правил heading
  обновляется до presentation;
- [`CompanionCombatPresentation`](../../game-server/src/com/aionemu/gameserver/services/ai/combat/CompanionCombatPresentation.java)
  публикует changed target через штатный `SM_TARGET_UPDATE`;
- если weapon state ещё не активен, устанавливает runtime-only
  `CreatureState.WEAPON_EQUIPPED` и рассылает штатный
  `SM_EMOTION(ATTACKMODE_IN_STANDING)` sighted players;
- затем единственный исходный `SM_ATTACK(time=0)` несёт actual result, а
  единственный target `onAttack` немедленно применяет damage;
- `EmotionType.ATTACK` не используется: swing остаётся исключительно штатным
  `SM_ATTACK`, а emotion только вводит Player в normal weapon mode;
- assist off/dismiss/flag off очищает target и weapon mode через
  `SM_TARGET_UPDATE` + `NEUTRALMODE_IN_STANDING`; task/future не создаётся;
- rejected gateway preflight, duplicate event или rejection внутри
  `PlayerController` не достигают presentation и damage.

Owner и второй observer получают один и тот же `SM_ATTACK`, если оба находятся
в KnownList companion. Self-send companion безопасно пропускается
`PacketSendUtility.sendPacket()`, потому что `isOnline()==false`; это не влияет
на KnownList iteration.

## 3. Варианты A/B/C/D

| Вариант | Плюсы | Минусы/риски | Оценка изменений | Решение |
|---|---|---|---|---|
| A: no-reward quarantine | Простая отрицательная гарантия; диагностический fallback | Лишает награды всех, griefing, companion ухудшает encounter | Reward veto + marker + lifecycle во многих hooks | Отклонить как product default |
| B: real group member | Готовая team distribution/UI | Slot, UI, invite/disconnect/connection lifecycle, отдельная share, owner получает меньше, persistence/double participation | Широкие group/lifecycle changes | Отклонить |
| C: owner contribution mapping | Повторяет summon/master; raw threat сохранён; native solo/group rewards; no duplicate | Узкий central resolver, transfer-on-remove, role/session/quest/death tests | Небольшие integration points в final projection и cleanup | Рекомендовать |
| D: separate reward pipeline | Гибкая future companion progression | Дублирует quest/drop/group/event rules, drift и double rewards | Новый reward engine и постоянная поддержка | Отклонить для native rewards |

Если в реализации вариант C потребует общего rewrite `DamageList`/reward
semantics, автоматически переключаться на A/B/D нельзя: остановиться и
зафиксировать blocker.

## 4. Role isolation

Текущий enum содержит `SyntheticPlayerRole.COMPANION`; в этом slice это exact
продуктовая роль `PERSONAL_COMPANION`:

- exact active owner/session;
- physical companion attacker, owner reward contributor;
- no native companion XP/quest/drop/kinah row;
- future отдельный companion profile.

Будущий `CITIZEN_AI`:

- не имеет owner mapping;
- resolver возвращает self/existing master semantics;
- имеет будущие собственные XP/inventory/equipment/loot/kinah/group/quests;
- не реализуется и не тестируется как функциональность в 2C, но role-isolation
  regression обязателен.

Не менять `Player.getMaster()` и не определять правило по `[AI]` prefix,
connection-null или synthetic registry вообще. Authority — exact role + exact
active session identity.

## 5. Trigger, gateway и state machine

`OwnerAttackSignalSource` attach-ит exact owner `ActionObserver` при `assist on`.
Immutable signal: monotonic sequence, target object ID + exact reference/
instance identity, skill ID, observed timestamp. Owner target selection и
`isInCombat()` не являются trigger.

```text
owner ObserveController ATTACK
  -> OwnerAttackSignalSource
  -> existing CompanionController.tick()
     -> CompanionCombatController
        -> CombatActionGateway.basicHitOnce
        -> existing SyntheticPlayerScheduler only
```

Минимальный `CombatActionGateway` отделён от movement gateway:

```text
basicHitOnce(OwnerAttackSignal) -> CombatResult
stop(reason) -> void
```

`CombatResult` — structured result, не expected exception. Нужны как минимум:
`HIT_STARTED`, `OUT_OF_RANGE`, `NO_LINE_OF_SIGHT`, `TARGET_NOT_ALLOWED`,
`TARGET_NOT_HOSTILE`, `TARGET_NOT_KNOWN`, `TARGET_NOT_VISIBLE`,
`MAP_OR_INSTANCE_MISMATCH`, `COOLDOWN`, `TARGET_INVULNERABLE`,
`COMPANION_DEAD`, `TARGET_DEAD`, `TARGET_UNSPAWNED`, `REMOVING`,
`EVENT_EXPIRED`, `DUPLICATE_EVENT`, `SESSION_MISMATCH`, `ROLE_MISMATCH`,
`UNSAFE_EQUIPMENT`, `RUNTIME_DISABLED`, `REJECTED_BY_PLAYER_RULES`.

State machine:

```text
IDLE --new valid owner event--> PREFLIGHT
PREFLIGHT --all checks pass--> one HIT_STARTED -> IDLE
PREFLIGHT --range/LoS/collision/identity reject--> structured result -> IDLE
any --assist off/dismiss/logout/map/flag/shutdown/death--> REMOVING
```

Один shared AI tick делает максимум одно bounded decision. Нет нового thread,
combat scheduler, auto-attack loop или `Future`. Cadence основан на current
`PlayerGameStats.getAttackSpeed()`, не на 200 ms scheduler period. В
gateway и `CreatureController` передаётся `time=0`, поэтому damage немедленный
и orphan delayed hit отсутствует; success подтверждается изменением attack
counter. Out-of-range/LoS/
collision не запускают approach: 2C не двигает
companion к цели, не повторяет событие и не реализует pursuit/pathfinding.

## 6. Spatial/target policy

Непосредственно перед hit проверить:

- exact active owner/companion session, lifecycle и world object identity;
- target exact `Npc`, template ID in allowlist и исходный `SpawnTemplate` exact
  `1234.05,1042.47,144.726`, heading `33`;
- `rating=NORMAL`, non-boss/non-elite, ordinary aggressive open-world target;
- owner/companion/target alive, target not invulnerable/about-to-die;
- exact map ID и instance ID equality;
- target в KnownList и `sees()` owner и companion;
- hostility через existing relation/`Player.isEnemy` и
  `PlayerRestrictions.canAttack`;
- current real attack range через `PlayerGameStats` и
  `PositionUtil.isInAttackRange`;
- `GeoService.canSee` owner-target и companion-target;
- geodata/LoS fail closed; no approach, teleport или pathfinding.

Reject `Player`, summon, servant, trap, door, siege object, friendly/neutral,
boss/elite/event/instance/scripted target и всё вне allowlist.

## 7. Reward integration design

Реализованный узкий контракт:

```text
CombatContributionOwnerResolver.resolve(physicalAttacker, rewardTarget, context)
  -> Creature contributionOwner
```

Default = `physicalAttacker.getMaster()`. Special mapping выполняется только
для exact registered PERSONAL_COMPANION session (`ACTIVE` для нового hit,
`REMOVING` только для уже нанесённого contribution до unregister). Resolver не
мутирует aggro/damage, не выдаёт reward и не зависит от имени.

Integration points:

1. `DamageList` использует resolver вместо прямого `getMaster()` для final PvE
   contribution. Raw `AggroList` остаётся неизменным.
2. `AggroList.transferDamagesToMaster()` использует тот же resolver, чтобы
   dismiss до NPC death не стирал legitimate owner contribution.
3. Lifecycle сначала убирает body из world/KnownLists (что вызывает transfer),
   затем unregister-ит session attribution mapping.
4. `NpcController` подавляет attack/add-hate quest hooks только для exact
   PERSONAL_COMPANION; native kill hook получает projected owner один раз.
5. Обычный summon/default result полностью сохраняется.

Unit/source-boundary tests доказывают, что owner damage `X` + companion damage
`Y` даёт одну owner row `X+Y`, owner team получает owner projection, cleanup
mapping живёт до transfer, а ordinary/default resolver сохраняет
`attacker.getMaster()`. Полный native reward и client-visible результат остаются
обязательной ручной проверкой.

## 8. Future companion progression seam

После native reward resolution сформировать immutable read-only event (пока
только спроектировать):

```text
CompanionEncounterResolvedEvent(
  companionProfileId, companionRuntimeObjectId,
  ownerObjectId, targetObjectId, targetTemplateId,
  rawCompanionDamage, participated, encounterResult,
  nativeContributionOwner, nativeTeamId, nativeRewardResult)
```

Будущий `CompanionProgressionService`:

- начисляет companion XP/bond/mastery в отдельный profile;
- может определять отдельный level/class/skills/stats/equipment и catch-up/
  normalization/cap-by-owner rules;
- переносит item ownership транзакционно, без копирования предмета;
- сохраняет только controlled profile, не runtime coordinates/name/online/
  combat state и не DB-template Cool;
- не вызывает native `NpcController.doReward()` повторно;
- не выдаёт второй quest credit/drop/loot/kinah/player XP.

Это событие и service не входят в код 2C; seam нужен, чтобы future progression
не превращался в отдельный native reward pipeline D.

## 9. Synthetic Player death

Обычный [`PlayerController.onDie()`](../../game-server/src/com/aionemu/gameserver/controllers/PlayerController.java)
небезопасен: cancel/use-item и общий death сопровождаются resurrection options,
instance/zone hooks, `PvpService`, EXP loss, `QuestEngine.onDie` и delayed tasks.

Нужен early path только exact PERSONAL_COMPANION:

1. atomically lifecycle `REMOVING`, assist off, invalidate signal;
2. stop decisions, movement, target, attack/cast and observer;
3. допустимый общий HP/death state и client-visible death/despawn;
4. cancel life/controller futures;
5. transfer raw contribution owner до unregister attribution mapping;
6. remove from `World`, `PlayerContainer`, scheduler, movement, registry/session;
7. не вызывать resurrection, EXP loss, PvP/quest/reward/zone progression,
   `PlayerLeaveWorldService`, `PlayerService.storePlayer()` или DAO save;
8. verify no restore future/reference; set clear `lastRemovalReason`.

Реализация должна быть узкой policy branch, не новым connection и не изменением
центральной модели `Player`.

## 10. Read-only preflight template Cool

Проверка выполнена 2026-08-02 только `SELECT` в существующем MySQL container.
Ниже не приводятся credentials.

| Объект | Фактическое состояние |
|---|---|
| Account `13` | `activated=0`, access level `0`, membership `0` |
| Character `106168` | DB name `Cool`, ELYOS, PRIEST, level/old level 1, EXP 1, DP 0, `online=0` |
| Position | map `210010000` Poeta; `1199.57, 1048.60, 138.898`, heading `80` |
| Life | HP `201`, MP `315`, FP `60` |
| Main hand | object `106171`, item `100100011` Training Mace, owner `106168`, equipped slot `1` |
| Weapon | MACE, PHYSICAL, level 1; damage 16–24, physical accuracy 102, magical accuracy 45, crit 10, parry 173, magical boost 80 |
| Range | weapon stat `1500`; `PlayerController` nominal stationary check is `1 + 1.5 = 2.5 m` plus its bounded covered-distance allowance |
| Attack speed | base `1500 ms`; active effect `10466` level 3 has `delta=-3%` per level, so current expected stat is `1365 ms`; runtime gateway must read the actual current stat |
| Armor | `110300292` Training Leather Armor (object `106172`, slot `8`); `113300278` Training Leather Leg Armor (`106173`, slot `4096`) |
| Accessories | none equipped in selected rows |
| Enchant/augment | equipped items enchant `0`, enchant bonus `0`, fusion `0`, charge `0`, tempering `0`, amplified `0`, buff `0` |
| Stones/godstone/idian | joined item-stone rows absent; no godstone/manastone/idian |
| Power shards | no equipped/active shard item found; first spike still requires runtime fail-closed check |
| Skills | IDs `39, 40, 41, 103, 243, 245, 302, 1838, 4012, 30001`, all level 1; 2C does not use them |
| Effects | `10465` level 3 movement speed (+30%) and `10466` level 3 attack speed (-9%), with active persisted remaining time at preflight |
| Cooldowns | no player cooldown or item cooldown rows |
| Quests | `1000 COMPLETE`, `1100 LOCKED` |
| Other inventory | starter consumables/currency objects owned by `106168`; no ownership mismatch |

Persistence risk audit:

- `PlayerService.getPlayer(..., false)` loads the body but does not schedule
  periodic saves; only `PlayerEnterWorldService` assigns
  `TaskId.PLAYER_UPDATE` and `TaskId.INVENTORY_UPDATE`. Existing companion
  lifecycle explicitly checks both absent.
- Basic attack mutates runtime HP/combat timestamps/counter and may dirty
  runtime state, but no leave/store path is called.
- Power-shard depletion in `CreatureController.attackTarget()` can invoke
  immediate `InventoryDAO.store()`; therefore runtime check must reject active
  shards even though SQL preflight found none.
- Godstone can proc effects; no stone is installed and godstone is forbidden.
- Persisted effects load and expiry tasks are runtime concerns. Their DB store
  is in `PlayerLeaveWorldService`; that service remains forbidden. Cleanup must
  cancel synthetic life/effect work without calling store.
- `ItemStoneListDAO` may repair invalid DB rows during load; current character
  has no stone rows. No basic-hit path should invoke DAO for this fixture.

Post-acceptance read-only snapshot 2026-08-03 после runtime spawn/combat/
dismiss также показал account `Test` (`id=13`) с `activated=0` и character
`Cool` (`id=106168`) с `online=0`. DB name, position
`1199.57,1048.60,138.898`, heading `80`, world `210010000`, EXP/DP `1/0`,
HP/MP/FP `201/315/60`, inventory, quests, effects и пустые player/item
cooldowns совпали с baseline. Runtime target, weapon/combat state, damage,
aggro и assist state в DB не появились; SQL write не выполнялся.

## 11. Выбранный NPC

Единственный allowlisted target первого spike:

| Поле | Значение |
|---|---|
| Template ID | `210115` |
| Имя | Juvenile Sparkie (`juvenile sparkie`) |
| Map | `210010000` Poeta, open world |
| Spawn | `1234.05, 1042.47, 144.726`, heading `33` |
| Level/HP | level 1, max HP 143 |
| Relation/AI | tribe `MONSTER`, AI `aggressive`, sensory range 8 |
| Rank/rating | `DISCIPLINED`, `NORMAL`; не elite/boss |
| Attack | range 2, attack speed 2142 ms |
| Respawn | 20 s |
| Drop | ordinary global drop group `SPAKY`; generic junk-material rule, без custom reward pipeline |

Почему подходит: это обычный hostile static open-world NPC близко к DB position
Cool, равного уровня, без instance/siege/event type, custom Java handler или
прямой ссылки в quest handlers/quest data. Точка `1234.05,1042.47` выбрана
явно, чтобы исключить одноимённые spawns. Перед ручным тестом owner должен
проверить, что у него нет активной цели, связанной с этим NPC; audit репозитория
не может доказать runtime quest journal конкретного owner.

Allowlist не спавнит NPC и не меняет game data. Если template/spawn/AI data
изменятся, startup/runtime validation отклоняет target до нового аудита.

## 12. Command permission для обычного owner

`commands.properties` задаёт `companion = 9`, но
[`AdminCommand.validateAccess()`](../../game-server/src/com/aionemu/gameserver/utils/chathandlers/AdminCommand.java)
принимает либо access level, либо exact grant из
[`CommandsAccessService.hasAccess()`](../../game-server/src/com/aionemu/gameserver/services/CommandsAccessService.java).

Существующий точечный механизм:

```text
//access add <OwnerName> companion
//access remove <OwnerName> companion
```

Grant хранится в `commands_access` и потому технически persistent, а не
автоматически expiring; для приёмки он используется как ограниченный по времени
операционный grant: admin выдаёт непосредственно перед тестом и удаляет сразу
после. Он не даёт остальные admin-команды. Встроенный
`giveTemporaryAccess()` доступен только для self-lowering flow `//access level`,
не для другого player, поэтому называть `//access add` настоящим ephemeral
permission нельзя.

`CompanionService.requireOwner()` проверяет exact owner instance/session и не
зависит от level 9. Обычный owner остаётся connected (`AionConnection != null`),
companion остаётся connectionless. Post-acceptance read-only SELECT 2026-08-03
обнаружил оставшийся grant `Calmer` (`player_id=106131`) -> `companion`.
Удалять его прямым SQL запрещено; владелец стенда должен выполнить
`//access remove Calmer companion`, после чего повторный SELECT должен быть
пустым.

Read-only pre/post check:

```sql
SELECT player_id, command
FROM commands_access
WHERE player_id = :owner_character_id AND command = 'companion';
```

## 13. Реализованные feature flags

Default-off keys в `game-server/config/main/ai.properties`:

```properties
ai.companions.combat.enabled = false
ai.companions.combat.basic_attack.enabled = false
ai.companions.combat.owner_attribution.enabled = false
ai.companions.combat.allowed_npc_ids =
ai.companions.combat.owner_signal_ttl_ms = 3000
```

Для spike все три booleans требуют также `ai.enabled=true` и
`ai.companions.enabled=true`; allowlist exact `210115`. Invalid/empty/duplicate/
oversized IDs fail startup or disable combat. Assist всегда reset off после
summon/restart. Runtime disable combat disarm-ит observer/action без обязательного
dismiss; global/companion disable использует полный cleanup 2A.

## 14. Фактически реализованный file scope

Новые AI-файлы:

- `services/ai/combat/CombatActionGateway.java`;
- `services/ai/combat/DefaultCombatActionGateway.java`;
- `services/ai/combat/CombatResult.java`, `CombatStatus.java`;
- `services/ai/combat/CompanionCombatController.java`,
  `CombatSignalSource.java`;
- `services/ai/combat/OwnerAttackSignal.java`,
  `OwnerAttackSignalSource.java`;
- `services/ai/combat/CombatContributionOwnerResolver.java`,
  `PersonalCompanionAttributionRegistry.java`;
- `services/ai/combat/CombatPreflightPolicy.java`,
  `CombatPreflightViolation.java`, `CombatNpcAllowlist.java`.

Минимальные integration points:

- [`CompanionController`](../../game-server/src/com/aionemu/gameserver/services/ai/CompanionController.java): существующий bounded maintenance callback вызывает combat tick alongside accepted follow/stay; отдельного scheduler/thread нет;
- [`CompanionService`](../../game-server/src/com/aionemu/gameserver/services/ai/CompanionService.java): assist/session/status and cleanup ordering;
- [`Companion` command](../../game-server/data/handlers/admincommands/Companion.java): `assist on|off|status`;
- [`AIConfig`](../../game-server/src/com/aionemu/gameserver/configs/main/AIConfig.java) and default config: default-off flags, no personal IDs;
- [`DamageList`](../../game-server/src/com/aionemu/gameserver/controllers/attack/DamageList.java): resolver at final projection;
- [`AggroList`](../../game-server/src/com/aionemu/gameserver/controllers/attack/AggroList.java): same resolver on transfer/removal и сериализация final snapshot/cleanup;
- [`NpcController`](../../game-server/src/com/aionemu/gameserver/controllers/NpcController.java): exact companion attack/add-hate quest guards;
- [`PlayerController`](../../game-server/src/com/aionemu/gameserver/controllers/PlayerController.java): exact companion attack-stage guards и ранний exact-session synthetic death branch.

Не менять `Player.getMaster()`, network packet handlers, client protocol,
schema, quest handlers, game data или persistence of template.

`CompanionEncounterResolvedEvent`, future `CompanionProgressionService` и
profile persistence не реализованы и не входят в 2C file scope.

## 15. Автоматические тесты

Pure unit/controller/gateway:

- selection/combat flag без ATTACK event не запускает action;
- assist default/off, stale TTL, duplicate event/tick и sequence dedup;
- exactly one `STARTED` hit per unique owner event;
- exact target/session/world identity, owner/companion/target life;
- allowlist, hostility, KnownList, visibility, map/instance;
- range, LoS/geodata, blocked direct approach, no teleport;
- cooldown uses current attack speed, independent of scheduler period;
- dismiss/runtime-disable during decision; structured outcomes no exceptions;
- no PvP, boss/elite/event/siege/instance, skills or auto-loop.

Attribution/reward:

- raw `AggroList` attacker remains companion and NPC threat can target it;
- owner `X` + companion `Y` -> one owner contribution `X+Y`, total unchanged;
- unrelated player row/share remains; companion has no second row;
- team owner mapping uses native team once and companion occupies no slot;
- dismiss before NPC death transfers `Y` owner exactly once;
- session mismatch/inactive mapping defaults safely and never maps CITIZEN_AI;
- ordinary player/summon/servant/trap/NPC encounters preserve baseline;
- quest `onAttack`/`onAddAggroList` not duplicated; owner kill credit at most once;
- XP/DP/AP/drop/loot/kinah and EventService outcomes at most once;
- third-party player is not denied reward merely because companion participated.

Lifecycle/persistence/source boundary:

- observer attach/detach identity-safe, no duplicate registration;
- dismiss/logout/map change/shutdown/flags clear combat/movement/target/mapping;
- contribution transfers before mapping unregister;
- synthetic death has no resurrection, EXP loss, PvP/quest/zone reward,
  restore future or save;
- no `PLAYER_UPDATE`/`INVENTORY_UPDATE`, DAO call or template state save;
- shard/godstone fixture rejected before hit;
- source checks forbid `CM_*`, `AionConnection`, direct HP, manual packets,
  direct `AttackUtil`, admin damage and separate scheduler/thread;
- stages 1/2A/2B remain green.

Real Player/NPC fixture or running game-server integration tests нужны для:

- actual `PlayerController` attack calculation/animation and raw aggro;
- KnownList/visibility/geodata/instance identity;
- `DamageList` -> `TeamDamageList` -> solo/group reward path;
- NPC death/drop/event hooks and third-party contribution;
- synthetic lethal damage/death cleanup;
- packet visibility двумя clients и SQL no-persistence snapshot.

Реализованы:

- `CompanionCombatControllerTest`: assist default/off, idempotent on/off,
  one gateway call per unique event, duplicate/expired/session/role rejects,
  runtime-disable и removal cleanup;
- `CombatPreflightPolicyTest`: structured fail-closed outcomes для PvP/non-NPC,
  hostility, KnownList/visibility, map/instance, range, LoS/geodata,
  dead/unspawned/invulnerable target, unsafe equipment и cooldown;
- `CombatNpcAllowlistTest`: только exact `210115`, malformed/extra IDs fail
  closed;
- `PersonalCompanionAttributionRegistryTest`: owner mapping, combined damage
  once, group-owner projection, removal mapping и idempotent unregister,
  role/session/CITIZEN isolation;
- `CompanionStage2CSourceBoundaryTest`: checked attack call, отсутствие fake
  connection/client packets/direct HP/DAO/отдельного scheduler, raw aggro
  boundary, quest suppression, death ordering и default-off flags; дополнительно
  доказывает один `SM_ATTACK`, один damage application, heading-before-packet,
  одинаковый normal/companion weapon packet, KnownList delivery и возврат
  duplicate/rejected preflight до presentation;
- `CompanionCombatControllerTest`: duplicate event не доходит до gateway, а
  removal synchronously disarm-ит signal source и вызывает stop без delayed
  presentation/future.

Полный узкий прогон пяти 2C test-классов: 31 test,
failures/errors/skips `0/0/0`. Полный AI suite и reactor фиксируются в разделе
19 и итоговом отчёте.

## 16. Ручная приёмка

Владелец проекта принял минимальный этап 2C в реальном клиенте 2026-08-03.
Фактически подтверждено:

1. Assist после summon по умолчанию выключен; `//companion assist on`
   подключает боевую реакцию, а trigger возникает только от атаки владельца.
2. NPC `210663` отклоняется с `TARGET_NOT_ALLOWED`; единственный allowlisted
   NPC `210115` успешно проходит preflight.
3. Companion поворачивается к target, достаёт экипированную Training Mace,
   переходит в weapon mode и выполняет настоящую basic weapon animation,
   видимую в реальном клиенте.
4. Checked player attack path наносит ровно один настоящий damage; NPC получает
   physical aggro на companion. Повторного или запоздалого hit нет.
5. `//companion assist off` detach-ит owner observer, очищает pending event,
   возвращает companion в neutral mode и визуально убирает оружие.
6. Fake `AionConnection`, симуляция `CM_ATTACK` и direct HP damage отсутствуют.
7. Регрессий accepted follow/stay и player presentation не обнаружено.
8. Post-acceptance SQL snapshot подтверждает отсутствие persistence runtime
   combat state в account/template player, inventory, quests, effects и
   cooldowns.

### 16.1. Deferred synthetic-death acceptance item

Natural death companion **не проверена вручную**. Единственный разрешённый
безопасный NPC `210115` наносит меньше damage, чем пассивная регенерация `Cool`,
поэтому естественно убить companion не может. Владелец сознательно отказался
от `//damage`; параметры и DB-состояние `Cool` не менялись, allowlist не
расширялся.

Synthetic early-death cleanup покрыт автоматическими unit/source-boundary
тестами, но его client-visible natural-death flow остаётся deferred acceptance
item. Тест обязателен перед разрешением более сильных NPC, сложных квестов,
instances или production rollout. Он не блокирует принятие минимального 2C.

Ручная приёмка 2C не распространяется на skills, autonomous combat, PvP,
bosses, elites, events, siege, instances, looting, companion progression,
persistence или `CITIZEN_AI`.

## 17. Cleanup, rollback и observability

Cleanup order:

1. lifecycle `REMOVING`; atomically assist off, reject signals/actions;
2. detach exact owner observer, invalidate sequence;
3. unregister shared scheduler combat decision;
4. stop movement/approach, clear exact target/current attack/cast;
5. cancel life/controller futures;
6. identity-safe world removal и KnownList cleanup; raw contribution transfer
   owner пока role/session attribution mapping ещё зарегистрирован;
7. remove PlayerContainer/runtime registry/movement/session references;
8. unregister attribution mapping last;
9. verify absence in world/container/registry/scheduler/movement/observer/
   combat/session/futures without save.

Status/log fields:

- owner/companion object IDs, role/session/lifecycle, connection-null;
- assist, combat state, signal sequence/skill/age and consumed marker;
- target object/template ID and exact spawn identity;
- known/visible/hostile/alive/invulnerable, map/instance/range/LoS;
- last `CombatStatus`, attack counter, retry/cooldown;
- physical contributor ID, resolved contribution owner/team, raw companion
  damage and transfer status;
- periodic tasks, scheduler/movement/observer registrations;
- removal reason and verified cleanup postconditions.

Не логировать passwords, DB URL, account name, personal template IDs from
override или inventory contents.

Rollback:

1. flags default false; operational rollback — `assist off`, combat flag false,
   затем при сомнении `//companion dismiss`;
2. detach observer/stop decisions while preserving accepted follow/stay if only
   combat disabled;
3. code rollback removes combat package/resolver/guards, leaving stages
   1/2A/2B;
4. migrations/tables/game-data rollback отсутствует;
5. full reactor and manual 1/2A/2B regression mandatory.

## 18. Stop conditions и blockers

Остановиться до/во время кода, если:

- attack требует fake connection, packet simulation/client patch, direct HP or
  administrative damage;
- owner mapping нельзя ограничить exact PERSONAL_COMPANION session/role;
- contribution учитывается дважды или исчезает при dismiss;
- ordinary summon/player/group/PvP behavior меняется непредвиденно;
- resolver требует изменить `Player.getMaster()` или широко переписать reward;
- quest/event/drop/instance semantics выбранного NPC оказываются специальными;
- basic hit запускает immediate DAO write для фактического Cool fixture;
- shard/godstone нельзя fail closed до hit;
- cleanup оставляет delayed hit/effect/life/observer/movement task;
- synthetic death нельзя остановить до resurrection/PvP/EXP/quest/persistence
  без существенного изменения центрального Player lifecycle;
- нужен отдельный thread/scheduler, teleport/full pathfinding;
- runtime template state требуется сохранить в DB.

Текущие открытые риски:

- resolver boundary используется `DamageList`, общий также для PvP; запрет PvP
  и regression обязательны;
- operational command grant persistent до явного remove, не auto-expiring;
- actual owner active quest и client-visible rewards проверяются только вручную;
- effect 10465/10466 runtime expiry требует cleanup observation;
- exact integration API для encounter-result event ещё не реализован;
- synthetic death branch затрагивает central controller и требует особенно
  строгой изоляции роли.

## 19. Build и read-only audit record

До изменения документов выполнен тот же Docker Maven/JDK 25 reactor:

```text
docker run --rm -v <repo>:/src -v /tmp/aion-stage1-m2:/root/.m2 \
  -w /src maven:3-eclipse-temurin-25 \
  mvn -B -T 4 -Dmaven.source.skip=true -Dmaven.test.skip=false test
```

Baseline result непосредственно перед Java-изменениями: `BUILD SUCCESS`;
game-server 90 + commons 1 = 91 tests, failures/errors/skips `0/0/0`;
reactor 6.243 s, completed 2026-08-02T13:46:35Z.

Финальная автоматическая проверка реализации:

- узкий 2C suite: 26/26;
- полный AI suite: 101/101;
- полный reactor: game-server 116 + commons 1 = 117 tests;
- failures/errors/skips: `0/0/0`;
- `BUILD SUCCESS`, completed 2026-08-02T14:22:57Z.

Автоматическая проверка первой timing-коррекции 2026-08-03 тем же
Docker Maven/JDK 25 процессом:

- узкий combat/presentation suite: 24/24;
- все AI tests: 105/105;
- полный reactor: game-server 120 + commons 1 = 121 test;
- failures/errors/skips: `0/0/0`;
- `BUILD SUCCESS`, completed 2026-08-03T13:25:31Z;
- `git diff --check`: успешно.

Это автоматическое подтверждение call-chain и safety boundaries, но не
client-visible результата. Ручная проверка после этого прогона снова не увидела
animation, поэтому timing-коррекция удалена.

Финальная автоматическая проверка target/weapon-mode коррекции 2026-08-03:

- узкий combat/presentation suite: 25/25;
- source-boundary tests 2C: 14/14;
- все AI tests: 106/106;
- полный reactor: game-server 121 + commons 1 = 122 tests;
- failures/errors/skips: `0/0/0`;
- `BUILD SUCCESS`, completed 2026-08-03T14:07:50Z.

Acceptance finalization run 2026-08-03 тем же Docker Maven/JDK 25 процессом:

- все пять узких 2C test-классов: 31/31, включая source-boundary 14/14;
- все AI tests: 106/106;
- полный reactor: game-server 121 + commons 1 = 122 tests;
- failures/errors/skips: `0/0/0`;
- `BUILD SUCCESS`, completed 2026-08-03T14:31:15Z;
- внутренние Markdown-ссылки ADR/плана и `git diff --check`: успешно.

Повторная client-visible проверка после target/weapon-mode correction успешно
пройдена; подтверждённые свойства и deferred death item перечислены в разделе
16. Минимальный этап принят владельцем проекта 2026-08-03.

Game-server в автоматических проверках не запускался. MySQL использован только
для read-only `SELECT`: `Cool` остаётся `online=0`, position/name/EXP/DP/HP/MP
совпадают с preflight; account `Test` остаётся `activated=0`. Schema/data,
account/player/items и online markers не изменялись.

## 20. Подтверждённые решения реализации

1. Принять вариант C с role/session-aware
   `CombatContributionOwnerResolver`.
2. Сохранить companion physical damage/threat source, но native contribution
   owner/group.
3. Использовать resolver и при transfer-on-remove до unregister session.
4. Подавлять companion attack-stage quest hooks, не перенаправляя их owner.
5. Разрешить narrow synthetic early death policy в `PlayerController`.
6. Утвердить единственный первый allowlist NPC `210115`, exact spawn
   `1234.05,1042.47,144.726`.
7. Утвердить один basic hit на уникальный owner ATTACK event, assist default off,
   no skills/auto-loop.
8. Утвердить default-off flags и proposed future file/test scope.
9. Для ручной приёмки использовать exact persistent/revocable command grant
   `//access add` -> test -> `//access remove`, поскольку auto-expiring grant для
   другого player отсутствует.

Все девять решений подтверждены владельцем проекта 2026-08-02. Implementation
preflight и код выполнены в указанной границе. После успешной проверки
presentation/combat flow в реальном клиенте минимальный этап 2C вручную принят
2026-08-03, а ADR-0003 переведён в `Accepted`. Synthetic death остаётся
автоматически покрытым, но вручную не проверенным deferred acceptance item.

## 21. Фактический lifecycle и call chain реализации

```text
//companion summon
  -> fully loaded Player body, clientConnection == null
  -> exact role/session attribution registered
  -> assist=false; owner ATTACK observer не attached

//companion assist on
  -> exact owner ActionObserver(ATTACK) attached
  -> latest-event bounded handoff (без queue/thread/scheduler)

owner real hit
  -> OwnerAttackSignal(owner/session/target/map/instance/eventId/TTL)
  -> existing SyntheticPlayerScheduler maintenance tick
  -> CompanionCombatController consumes event once
  -> DefaultCombatActionGateway fail-closed preflight
  -> PlayerController.attackTarget(target, 0, false)
  -> active companion heading set toward target
  -> SM_TARGET_UPDATE if target changed
  -> WEAPON_EQUIPPED + ATTACKMODE_IN_STANDING if entering combat mode
  -> CreatureController one actual calculation + one SM_ATTACK(time=0) broadcast
     + one immediate damage application
  -> AggroList raw attacker = physical companion
  -> DamageList resolver maps final contribution to exact owner
  -> existing solo/team reward path exactly once

assist off / flag off / dismiss / logout / map change / shutdown / death
  -> reject new decisions and detach exact observer
  -> stop combat/movement and clear target/tasks
  -> mark attribution REMOVING
  -> World/KnownList removal transfers raw contribution to owner
  -> unregister runtime/session and attribution mapping last
  -> no PlayerEnter/LeaveWorldService, periodic save or DAO persistence
```

Synthetic death вызывает только общий client-visible death state/effect
cleanup из `CreatureController.onDie()`, затем немедленный companion cleanup.
Branch возвращается до resurrection, instance/zone, PvP, EXP-loss и quest-death
частей обычного `PlayerController.onDie()`.

Известное ограничение transfer: штатный `AggroList` переносит damage только
если owner ещё известен target. При owner logout/map change native awareness
может корректно отклонить offline/out-of-map contributor; самостоятельной
фоновой награды или offline persistence 2C не добавляет.

## 22. Конфигурация, deployment, наблюдаемость и rollback

В пользовательском `docker/server-config/game/mygs.properties` для ручного
spike нужны (template IDs остаются только в локальном override):

```properties
ai.enabled = true
ai.companions.enabled = true
ai.companions.template_account_id = <closed_account_id>
ai.companions.template_character_id = <Cool_character_id>
ai.companions.combat.enabled = true
ai.companions.combat.basic_attack.enabled = true
ai.companions.combat.owner_attribution.enabled = true
ai.companions.combat.allowed_npc_ids = 210115
ai.companions.combat.owner_signal_ttl_ms = 3000
```

Пересборка и пересоздание только game-server:

```bash
docker compose -f docker-compose.db.yml -f docker-compose.servers.yml build game
docker compose -f docker-compose.db.yml -f docker-compose.servers.yml \
  up -d --no-deps --force-recreate game
docker compose -f docker-compose.db.yml -f docker-compose.servers.yml \
  logs -f game | grep -E 'AI_COMPANION_COMBAT|AI_COMPANION lifecycle'
```

Ожидаемые structured actions: `ASSIST result=ENABLED|DISABLED`,
`OWNER_ATTACK_EVENT result=ACCEPTED`, `BASIC_HIT result=HIT_STARTED` либо exact
reject reason, `FINAL_REWARD result=MAPPED`,
`CONTRIBUTION_TRANSFER result=TRANSFERRED|SKIPPED`,
`SYNTHETIC_DEATH result=CLEANUP_REQUESTED`, затем companion lifecycle removal
с `persistence=false`.

Rollback без schema/data migration: `//companion assist off`, затем
`//companion dismiss`; вернуть три combat booleans в `false`, пересобрать и
пересоздать game-container. Stages 1/2A/2B остаются доступными, если выключены
только combat flags.
