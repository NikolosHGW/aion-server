# Этап 2D: tracking настоящего quest 1102 и quest-scoped combat

Статус: принят вручную владельцем проекта 2026-08-09

Дата аудита: 2026-08-03

Baseline: branch `feature/ai-mvp`, commit
`ba6320ce107c7ad5fa1e5ae12e6f5698d3c18dfe` (`feat(ai): add
server-controlled companion combat`). Этапы 1, 2A, 2B и 2C приняты;
[ADR-0001](ADR-0001-SERVER-CONTROLLED-PLAYER.md),
[ADR-0002](ADR-0002-READ-ONLY-QUEST-GOAL-PLANNING.md) и
[ADR-0003](ADR-0003-SERVER-CONTROLLED-COMBAT-ACTIONS.md) имеют статус
`Accepted`. Реализуемое решение 2D описано в
[ADR-0004](ADR-0004-COMPANION-QUEST-EXECUTION-TRACKING.md), который после
ручной приёмки имеет статус `Accepted`.

## 0. Итог аудита

Минимальный 2D можно построить без fake connection, client changes, quest
dialog simulation, отдельного scheduler, shadow progress, нового persistence и
изменения центрального `Player`.

Рекомендуется:

- owner сам принимает и сдаёт exact quest 1102 штатным клиентом;
- `today` и `goal choose` остаются read-only;
- runtime tracker раз в 1000 ms читает exact owner `QuestState` в уже
  существующем `SyntheticPlayerScheduler`;
- сообщения возникают только при semantic transition;
- отдельная quest policy разрешает checked basic hit только по текущим
  `210133/210134` и только пока настоящий journal содержит active incomplete
  1102;
- final credit/reward остаётся в принятом owner-attribution path 2C.

После отдельного подтверждения владельца узкая архитектура реализована только
в AI integration layer, AI configuration defaults, admin-command formatter и
тестах. Центральные `Player`, quest core, packet handlers, DAO, SQL/schema,
quest/game XML и данные игроков не изменялись.

## 1. Доказанные данные quest 1102

Источники:

- [`quest_data.xml`](../../game-server/data/static_data/quest_data/quest_data.xml);
- [`poeta.xml` quest scripts](../../game-server/data/static_data/quest_script_data/poeta.xml);
- [`MonsterHuntData`](../../game-server/src/com/aionemu/gameserver/questEngine/handlers/models/MonsterHuntData.java);
- [`MonsterHunt`](../../game-server/src/com/aionemu/gameserver/questEngine/handlers/template/MonsterHunt.java).

| Поле | Доказанное значение |
|---|---|
| ID / name | `1102`, `Kerubar Hunt`, `nameId=1102202` |
| Race / zone | `ELYOS`, Poeta |
| Level | minimum 1; явного maximum нет |
| Category | `IMPORTANT` |
| Repeat | `max_repeat_count=1`, non-repeatable |
| Share | `cannot_share=true` |
| Report | `can_report=true` |
| Prerequisite | finished quest `1101` |
| Handler | generic XML `monster_hunt`, не custom Java handler |
| Start NPC | `203057` Mires |
| End NPC | `203057` Mires: `end_npc_ids` отсутствует, поэтому constructor `MonsterHunt` копирует start IDs |
| Objective | old-style `quest_kill`, step 0, var 0 |
| Targets | `210133` и `210134`, Striped Kerub |
| Count | общий `3` для множества target IDs |
| Reward | `400` kinah и `180` XP; reward items отсутствуют |

Quest 1101 (`Sleeping on the Job`) также Elyos/Poeta/IMPORTANT,
non-repeatable, level 1, reward 120 kinah + 130 XP. Generic `report_to`
указывает start NPC `203049` Elpas и end NPC `203057` Mires.

## 2. Read-only preflight Calmer и Cool

Snapshot получен 2026-08-03 только `SELECT`/`SHOW` из контейнерного MySQL.
Пароли не читались и не выводились.

### 2.1. Calmer

| Поле | Значение |
|---|---|
| Account | ID `1`, name `Nikolos`, activated `1`, access level `0`, membership `0` |
| Character | ID `106131`, name `Calmer` |
| Race / class / level | `ELYOS`, `MAGE`, persisted `old_level=4`, EXP `3830` |
| Position | Poeta `210010000`, world owner `0`, `1231.79, 1043.32, 144.063`, heading `58` |
| Online | `0` на момент snapshot |
| HP / MP / DP | `227 / 767 / 0`; FP `60` |
| Quests | `1000 COMPLETE`; `1100 START`; `1205 REWARD`; строк `1101` и `1102` нет |
| Cooldowns / effects | player cooldowns `0`, item cooldowns `0`, persistent effects `0` |
| Exact grant | единственная строка Calmer в `commands_access`: `companion` |

Inventory snapshot Calmer:

| Item unique ID | Item ID | Count | Equipped / slot |
|---:|---:|---:|---|
| 106132 | 182400001 | 1000 | no / 65535 |
| 106133 | 100600034 | 1 | yes / 1 |
| 106134 | 110100009 | 1 | yes / 8 |
| 106135 | 113100005 | 1 | yes / 4096 |
| 106136 | 160000001 | 12 | no / 0 |
| 106137 | 169300002 | 19 | no / 1 |
| 106138 | 162000002 | 100 | no / 2 |
| 106139 | 162000007 | 100 | no / 3 |
| 106140 | 164002116 | 49 | no / 4 |
| 106141 | 164002117 | 49 | no / 5 |
| 106142 | 164002118 | 50 | no / 6 |
| 106143 | 169620005 | 2 | no / 7 |
| 106298 | 167000228 | 1 | no / 8 |

Обычный quest-log limit равен 40
([`custom.properties`](../../game-server/config/main/custom.properties)).
[`QuestService.checkQuestListSize()`](../../game-server/src/com/aionemu/gameserver/services/QuestService.java)
считает только active category `QUEST`; текущие active Calmer имеют категории
MISSION/IMPORTANT. Значит capacity не является blocker: active normal count 0,
после 1102 он также не увеличится, потому что 1102 — IMPORTANT.

### 2.2. Eligibility Calmer

Calmer **сейчас не eligible** для 1102: prerequisite 1101 не имеет state
COMPLETE. Отсутствие строки 1102 доказывает, что в текущем штатном journal нет
active/completed non-repeatable 1102. Reward 1102 не содержит предметов,
поэтому inventory не может независимо доказать её получение; текущий EXP также
не позволяет атрибутировать исторические 180 XP. Только при внешнем ручном
удалении DB history это доказательство могло бы быть потеряно; такой операции
аудит не обнаруживал и не выполнял.

Штатный путь подготовки без SQL/admin mutation:

1. Войти Calmer обычным клиентом.
2. Принять quest 1101 у Elpas `203049` в Poeta, spawn
   `1204.29,1053.18,138.962`, heading 116.
3. Сдать 1101 Mires `203057`, spawn `1141,1032,128.875`, heading 3.
4. Убедиться клиентом и read-only SQL, что 1101 стал COMPLETE, а 1102 ещё
   отсутствует.
5. Только затем начинать acceptance 2D.

Отдельный расходный персонаж не обязателен. Он нужен лишь если владелец хочет
сохранить текущий journal Calmer неизменным.

### 2.3. Cool/template baseline

| Поле | Значение |
|---|---|
| Account | ID `13`, name `Test`, activated `0`, access level `0` |
| Character | ID `106168`, DB name `Cool`, Elyos PRIEST level 1 |
| EXP / DP | `1 / 0` |
| Position | `1199.57,1048.6,138.898`, heading 80, Poeta `210010000`, world owner 0 |
| Online | `0` |
| HP / MP / FP | `201 / 315 / 60` |
| Quests | `1000 COMPLETE`, `1100 LOCKED`; 1102 отсутствует |
| Cooldowns | player `0`, item `0` |
| Effects | skill `10465` lvl 3 и `10466` lvl 3; это persistent template state, не runtime 2D data |

2D не должен менять или сохранять Cool: quest и rewards принадлежат Calmer.

### 2.4. Осознанное local test grant

Persistent grant `player_id=106131, player=Calmer, command=companion` оставлен
намеренно по решению владельца. Его нельзя удалять в конце local acceptance и
нельзя менять SQL. Account access level остаётся 0; других exact per-character
grant rows snapshot не показал. Перед public/production rollout grant должен
быть отдельно пересмотрен или удалён. Реализация 2D не должна расширять его на
другие admin commands.

## 3. NPC templates, spawns и data safety

Источники:

- [`npc_templates.xml`](../../game-server/data/static_data/npcs/npc_templates.xml);
- [`210010000_Poeta.xml`](../../game-server/data/static_data/spawns/Npcs/210010000_Poeta.xml);
- [`rules_junk_materials.xml`](../../game-server/data/static_data/global_drops/rules/rules_junk_materials.xml).

| NPC | Level | HP | Rating / rank | AI / hostility | Sensory / range / speed | Spawn count / respawn |
|---|---:|---:|---|---|---|---|
| 203057 Mires | 20 | 2961 | NORMAL / DISCIPLINED | general Elyos | 20 / 2 / 2000 ms | 1 / 295 s |
| 210133 Striped Kerub | 1 | 143 | NORMAL / DISCIPLINED | aggressive MONSTER | 7 / 2 / 2100 ms | 18 / 15 s |
| 210134 Striped Kerub | 2 | 199 | NORMAL / DISCIPLINED | aggressive MONSTER | 7 / 2 / 2100 ms | 23 / 15 s |

Оба quest target template IDs встречаются в static spawn data только в Poeta
`210010000`. Специальных Java AI/quest/event handlers для них нет. Оба имеют
generic drop group `CHERUBIM`; global junk-material rule имеет chance 40% при
level difference от -4 до +5. Loot остаётся штатным owner/team flow, companion
не принимает loot decisions.

### 3.1. Spawn 210133

Все 18 coordinates из game data:

```text
1038.58,989.604,129.484,h93
1050.87,996.053,131.596,h111
1052.9,1070.34,117.854,h37
1055.42,962.302,133.088,h69
1063,1079.48,117.494,h7
1064.42,1064.27,123.261,h29
1072.1,1073.21,122.555,h97
1073.99,1057.03,125.867,h30
1097.29,977.352,130.113,h118
1098.26,1000.43,125.6,h93
1102.11,999.931,126.5,h10
1111.75,998.109,127.321,h115
1124.27,1018.68,127.584,h115
1147.76,997.14,135.315,h62,walker
1153.9,995.87,137,h93
1191.35,1136.9,139.6,h71
1199.99,1127.68,142.085,h30
1210.93,1133.86,144.74124,h106,walker
```

### 3.2. Spawn 210134

Все 23 coordinates:

```text
1006.67,1214.79,101.966,h15
1010.19,1222.48,101.82,h114
1015.85,1119.46,111.815,h67
1018.38,1217.89,102.166,h2
1025.49,1118.9,115.368,h0(default)
1029.75,1214.32,103.528,h16
1031.98,1135.02,115.289,h82
1038.61,1110.88,117.203,h52
1040.01,1139.44,117.269,h35
1040.21,1186.65,111.476,h65
1053.64,1184.29,114.463,h100
1054.72,1132.38,119.875,h93
1058.61,1176.98,116.347,h49
1064.15,1173.57,117.528,h101
1066.23,1125.47,120.116,h114
1068.78,1135.55,120.568,h10
1071.86,1126.84,120.116,h91
1073.19,1104.39,119.345,h58
1080.35,1111.82,121.305,h82
1108.24,1191.62,127.397,h96
1109.56,1173.8,126.625,h97
1111.61,1178.84,126.496,h72
1126.45,1182.56,129.329,h14
```

Для первого slice предлагается fail-closed разрешить 39 non-walker spawn
fingerprints и отклонить два walker spawn 210133. Большинство записей не имеют
явного `static_id`, поэтому одного ID недостаточно: runtime target должен иметь
не temporary/event spawn, expected map/template, `walkerId == null`,
`randomWalkRange == 0` и исходные coordinate/heading в малом epsilon.

## 4. Точный quest lifecycle

### 4.1. Acceptance через настоящий клиент

```text
CM_DIALOG_SELECT.runImpl()
  -> connection.getActivePlayer()
  -> target из owner KnownList
  -> DialogService.isInteractionAllowed()
  -> target.getController().onDialogSelect()
  -> QuestEngine.onDialog()
  -> MonsterHunt.onDialogEvent(QUEST_ACCEPT*)
  -> AbstractQuestHandler.sendQuestStartDialog()
  -> QuestService.startQuest()
  -> new QuestState(1102, START) + QuestStateList.addQuest()
  -> SM_QUEST_ACTION(ADD) только настоящему owner client
```

Ключевые файлы:
[`CM_DIALOG_SELECT`](../../game-server/src/com/aionemu/gameserver/network/aion/clientpackets/CM_DIALOG_SELECT.java),
[`QuestEngine`](../../game-server/src/com/aionemu/gameserver/questEngine/QuestEngine.java),
[`AbstractQuestHandler`](../../game-server/src/com/aionemu/gameserver/questEngine/handlers/AbstractQuestHandler.java),
[`QuestService`](../../game-server/src/com/aionemu/gameserver/services/QuestService.java).

Только этот connected-owner path принимает квест. AI tracker не вызывает ни
один его метод.

### 4.2. Kill, progress и client update

```text
NpcController.doReward()
  -> AggroList.getFinalDamageList()
  -> DamageList: raw companion -> exact owner, owner damage объединён
  -> TeamDamageList
  -> solo: QuestEngine.onKill(owner) один раз на owner contribution
     group: PlayerTeamDistributionService.accept() один раз на каждого
            online/in-range ordinary member
  -> MonsterHunt.onKillEvent(owner, target)
  -> если QuestStatus.START и target 210133|210134:
       read var0; increment максимум на 1, не выше 3
       QuestState.setQuestVarById(0, value)
       SM_QUEST_ACTION(UPDATE) настоящему owner client
```

Progress читается как `clamp(qs.getQuestVarById(0), 0, 3)`. Quest 1102 —
old-style MonsterHunt, `reward=false` и `rewardNextStep=false`, поэтому при 3/3
status остаётся START. `READY_TO_TURN_IN` определяется как START + var0 >= 3,
а не как REWARD.

### 4.3. Turn-in, reward и COMPLETE

```text
owner dialog с Mires 203057
  -> MonsterHunt.onDialogEvent(SELECT_QUEST_REWARD)
  -> проверка total var >= 3
  -> QuestState.setStatus(REWARD) + SM_QUEST_ACTION(UPDATE)
  -> следующий штатный reward selection
  -> AbstractQuestHandler.sendQuestEndDialog()
  -> QuestService.finishQuest()
  -> giveReward(owner): 400 kinah + 180 XP
  -> QuestState.setStatus(COMPLETE), completeCount++, var0=0
  -> SM_QUEST_ACTION(UPDATE)
  -> QuestEngine.onQuestCompleted()
```

Tracker считает COMPLETE только по настоящему `QuestStatus.COMPLETE`, а не по
своему сообщению, var0 или combat event.

### 4.4. Persistence, abandon, logout и restart

- Каждый setter [`QuestState`](../../game-server/src/com/aionemu/gameserver/questEngine/model/QuestState.java)
  помечает native state NEW/UPDATE_REQUIRED.
- [`PlayerQuestListDAO`](../../game-server/src/com/aionemu/gameserver/dao/PlayerQuestListDAO.java)
  хранит `status`, `quest_vars`, `flags`, `complete_count`, repeat/reward/time.
- [`PlayerService.storePlayer()`](../../game-server/src/com/aionemu/gameserver/services/player/PlayerService.java)
  вызывает DAO при обычном periodic/logout persistence connected owner.
- `CM_DELETE_QUEST` вызывает `QuestService.abandonQuest()`. Для незавершённого
  non-repeatable 1102 state удаляется из `QuestStateList`, owner получает
  штатный ABANDON packet, а DAO позже удаляет строку.
- Tracker никогда не вызывает abandon/store. `goal clear` очищает только
  runtime execution.
- Owner logout/map/instance change уже dismiss-ит companion через lifecycle
  2A, что detaches combat observer и очищает runtime goal. Native quest owner
  сохраняется обычным flow.
- После server restart runtime goal не восстанавливается автоматически.
  Повторный `today` может read-only обнаружить START/REWARD и предложить
  reattach. COMPLETE non-repeatable даёт terminal `ALREADY_COMPLETE` без offer.

## 5. Tracking variants

| Вариант | Плюсы | Недостатки/риски | Решение |
|---|---|---|---|
| A. Event-driven | Немедленная реакция; нет polling | ObserverType не имеет quest events; completion callback неполон; нужны центральные hooks для start/progress/abandon и сложный detach/dedupe | Не выбирать для minimal 2D |
| B. Budgeted read-only | O(1) lookup; journal — truth; общий scheduler; минимальный integration surface | До 1 s latency; нужна session history для ABANDONED | Рекомендуется |
| C. Shadow counter | Простая реакция на combat signal | Расходится с journal/group/restart/handler; второй источник истины; риск double progress | Запрещён как authoritative source |

Scheduler period сейчас default 200 ms, max actions 1, budget 2 ms
([`ai.properties`](../../game-server/config/main/ai.properties)). Tracker
использует bounded due timestamp и читает quest не чаще одного раза в 1000 ms.
Для одного companion это не создаёт отдельной queue/future. Наблюдаемость:
poll count, transition count, duplicate-suppressed count, last poll duration,
last state/reason; предупреждение при выходе за budget.

## 6. Реализованная runtime state machine

| State | Источник | Next step / combat |
|---|---|---|
| `NO_GOAL` | Нет offer/chosen | `today`; quest combat запрещён |
| `OFFERED` | 2B plan предложен | `goal choose`; combat запрещён |
| `CHOSEN_WAITING_ACCEPTANCE` | Chosen есть, 1102 state ещё null | Принять у 203057; combat запрещён |
| `ACTIVE_OBJECTIVE` | qs START, var0 0..2 | Убивать 210133/210134; quest combat может быть разрешён |
| `READY_TO_TURN_IN` | qs START, var0 >=3 | Вернуться к 203057; quest combat запрещён |
| `COMPLETED` | qs COMPLETE | Runtime goal terminal/clear after one transition message |
| `ABANDONED` | Ранее видимый START/REWARD исчез в той же session | Настоящий quest отменён; combat запрещён |
| `STALE` | Plan/template/allowlist/session fingerprint изменился | Clear/replan; combat запрещён |
| `BLOCKED` | Unsupported status/identity/runtime invariant | Диагностика; combat запрещён |

REWARD кратковременно читается как `READY_TO_TURN_IN` до COMPLETE. LOCKED для
1102 считается BLOCKED. Progress fingerprint включает только semantic state,
clamped current/required и reason; одинаковый fingerprint не отправляется
повторно.

Lifecycle:

```text
summon -> CompanionSession + goal session
today -> OFFERED (read-only plan)
choose -> execution session / WAITING_ACCEPTANCE
shared tick (throttled) -> read owner QuestState -> transition + one message
owner ATTACK -> combat preflight -> legacy 2C or quest policy -> one hit
clear -> execution session only cleared; owner quest untouched
dismiss/logout/map change/shutdown -> detach observer, clear execution,
                                      normal synthetic cleanup; no quest mutation
```

## 7. Commands and deterministic UX

Новая обязательная команда не нужна:

```text
//companion today
//companion goal choose
//companion goal status
//companion goal clear
```

`goal status` должен read-only показывать:

- quest ID и localized client link;
- execution state и last transition reason;
- start/end NPC ID/name и reference coordinate;
- current owner map/instance;
- objective target names/IDs;
- progress `0/3..3/3` из настоящего `QuestState`;
- следующий конкретный шаг;
- route limitation: reference coordinates only, no route/pathfinding;
- `ownerAcceptsAndTurnsIn=true`, `aiQuestMutation=false`;
- poll interval, last snapshot age и suppressed duplicate count.

Transition messages — только deterministic formatter/client links:

1. Нужно принять quest у Mires.
2. Quest принят, progress 0/3.
3. Progress изменился 1/3 или 2/3.
4. Objective выполнен 3/3; вернуться к Mires.
5. Quest завершён штатно.
6. Quest отменён, stale или blocked.

LLM/free text, synthetic quest packets и dialog от companion запрещены.

## 8. Quest-scoped combat authorization

Существующий
[`DefaultCombatActionGateway`](../../game-server/src/com/aionemu/gameserver/services/ai/combat/DefaultCombatActionGateway.java)
жёстко разрешает только exact 2C NPC 210115/spawn. 2D не должен превращать
его config в широкий список target IDs.

Реализованная композиция:

```text
OwnerAttackSignal
  -> common identity/TTL/assist/removal/cadence preflight
  -> LegacySpikeAuthorizationPolicy(210115 exact spawn)
     OR QuestCombatAuthorizationPolicy(exact active execution plan)
  -> common hostile/KnownList/visibility/range/LoS/equipment checks
  -> PlayerController.attackTarget(target, 0, false)
  -> один checked damage + принятая presentation 2C
```

Quest policy разрешает hit только когда одновременно истинно:

- feature flags global/companion/combat/basic/attribution/quest execution и
  quest combat включены;
- exact active companion registry/session, role `COMPANION` с продуктовой
  семантикой PERSONAL_COMPANION, exact owner и session ID;
- есть chosen immutable execution plan quest 1102; quest ID входит в
  configured quest allowlist;
- live owner `QuestState` exact START и `var0 < 3`;
- target template входит в current `QuestKillObjective` (`210133|210134`);
- owner, companion и target — Poeta `210010000`, exact same instance;
- target class exact ordinary `Npc`, `NpcObjectType.NORMAL`, rating NORMAL,
  not boss/raid/elite/event/siege/instance;
- target spawn совпадает с одним immutable approved non-walker fingerprint;
- target alive/spawned/vulnerable/hostile;
- owner и companion знают и видят target; attack range и обе LoS/geodata
  проверки проходят;
- assist включён, и signal — настоящий unique owner `ObserverType.ATTACK`
  event exact target с действующим TTL;
- equipment/cadence checks 2C проходят.

При отсутствии active quest, после 3/3, в REWARD/COMPLETE/ABANDONED/STALE,
при wrong map/instance или target quest branch fail-closed. Legacy 210115
сохраняет прежнее независимое поведение 2C.

Config model: allowlist содержит quest `1102`; target IDs/count/map/spawn
manifest строятся из audited immutable quest/static data. Отдельный ручной
`allowed_npc_ids=210133,210134` для 2D не добавляется.

## 9. Доказательство quest credit и reward safety

Принятый flow 2C:

1. `NpcController.onAttack()` записывает raw companion damage/threat.
2. [`DamageList`](../../game-server/src/com/aionemu/gameserver/controllers/attack/DamageList.java)
   вызывает `CombatContributionOwnerResolver.resolve()` для каждой raw строки.
3. Raw owner и raw companion дают один map key owner; damage суммируется, но
   строка не дублируется.
4. [`TeamDamageList`](../../game-server/src/com/aionemu/gameserver/controllers/attack/TeamDamageList.java)
   при наличии обычной group owner отображает эту одну contribution на team.
5. Solo `NpcController.doReward()` вызывает `QuestEngine.onKill()` один раз для
   owner row. Group
   [`PlayerTeamDistributionService.PlayerTeamRewardStats.accept()`](../../game-server/src/com/aionemu/gameserver/model/team/common/service/PlayerTeamDistributionService.java)
   вызывает onKill один раз на каждого online/in-range member; companion не
   является member.
6. `MonsterHunt.onKillEvent()` для exact target увеличивает var0 максимум на 1.

Следовательно:

- companion partial hit + owner lethal hit: одна aggregated owner row, один
  owner quest increment;
- owner partial hit + companion lethal hit: тот же результат; lethal identity
  не создаёт отдельной companion reward row;
- companion-only lethal hit после настоящего owner trigger: contribution всё
  равно owner, один quest increment;
- XP/DP/AP/drop rights используют штатную owner/team contribution;
- companion не получает native reward, quest state или loot ownership;
- настоящий quest reward выдаётся один раз только `QuestService.finishQuest()`;
- attack-stage hooks synthetic companion остаются подавлены как в 2C, а
  ordinary owner hooks не перенаправляются и не подавляются;
- third-party/group flow остаётся существующим и требует regression tests.

Нельзя добавлять ручной `QuestEngine.onKill`, shadow increment, direct var
setter или второй reward hook: любой из них создаст риск double credit.

## 10. Deferred synthetic death

210133/210134 — aggressive NORMAL mobs, level 1/2, HP 143/199, attack cadence
2100 ms. По
[`NpcStatCalculation`](../../game-server/src/com/aionemu/gameserver/model/stats/calc/NpcStatCalculation.java)
их calculated base physical attack приблизительно 6 и 13 до defense/random
hit rules. Cool имеет persisted HP 201. Его natural standing regen base для
level 1 равен `level + 3`, модифицирован health stat, каждые 6 s
([`PlayerGameStats.getHpRegenRate()`](../../game-server/src/com/aionemu/gameserver/model/stats/container/PlayerGameStats.java),
[`LifeStatsRestoreService`](../../game-server/src/com/aionemu/gameserver/services/LifeStatsRestoreService.java)).

Из static data нельзя доказать, что одиночный Kerub стабильно перебьёт
effective defense/avoidance/regen Cool до того, как Calmer закончит бой.
Несколько aggressive mobs повышают риск, но это не детерминированный acceptance
method. Текущий synthetic early-death lifecycle автоматически покрыт тестами,
но natural death вручную не проверена в 2C.

Решение владельца от 2026-08-03: deferred natural-death test не блокирует
Java-реализацию, локальное включение quest-execution/quest-combat flags и
ручную проверку 1102. Специально добиваться смерти в 2D не требуется; Cool,
его HP/regen и DB-state не меняются, `//damage` не используется. Если Kerub
убьёт companion естественно, фактический lifecycle и логи фиксируются.

Тест остаётся обязательным перед public/production rollout и перед более
сильными NPC, сложными quest, instances, bosses, siege и иным опасным
контентом. Это принятое ограничение минимального 2D, а не утверждение о ручной
проверке natural death.

## 11. Feature flags и rollback

Реализованные параметры; оба action flags `false` по умолчанию, allowlist в
base config пустой:

```properties
ai.companions.quest_execution.enabled = false
ai.companions.quest_execution.combat_enabled = false
ai.companions.quest_execution.allowed_quest_ids =
ai.companions.quest_execution.poll_interval_ms = 1000
```

В implementation preflight значение allowlist допускается только exact `1102`,
poll interval — bounded, например 500..5000 ms. Существующие global,
companion, 2B goal и 2C combat flags также обязательны.

Rollback:

1. Выключить `quest_execution.combat_enabled`, затем
   `quest_execution.enabled`.
2. На следующем shared tick detached execution очищается; combat observer 2C
   может остаться только для legacy 210115, если его flags включены.
3. Проверить status/log: execution отсутствует, quest targets rejected,
   scheduler/movement/lifecycle 2A штатны.
4. Не вызывать abandon и не менять owner quest. Если 1102 уже принят, Calmer
   продолжает его как обычный Player.
5. Код откатывается без DB migration; schema/data новых сущностей нет.

## 12. Фактический file scope реализации

Product/integration files:

- `game-server/src/com/aionemu/gameserver/configs/main/AIConfig.java`;
- `game-server/config/main/ai.properties` — только defaults false и safe bounds;
- `game-server/src/com/aionemu/gameserver/services/ai/CompanionService.java`;
- `game-server/data/handlers/admincommands/Companion.java` — расширение status,
  без новой обязательной команды;
- `CompanionGoalSession.java`, `QuestGoalCommandFormatter.java` и новый
  structured `QuestGoalProposalResult` — reattach/`ALREADY_COMPLETE` boundary;
- `game-server/src/com/aionemu/gameserver/services/ai/combat/DefaultCombatActionGateway.java`
  — узкая authorization composition без изменения единственного checked hit;
- новый `services/ai/quest/CompanionQuestExecutionRuntime.java`;
- новые `services/ai/quest/CompanionQuestExecutionTracker.java`;
- новые immutable execution session/snapshot/state/transition/context types;
- новые `QuestExecutionPlan`, `QuestExecutionPlanProvider`, exact allowlist и
  immutable authorization manifest/spawn fingerprints;
- новый deterministic `QuestExecutionCommandFormatter.java`;
- новый `services/ai/combat/QuestCombatAuthorizationPolicy.java`;
- соответствующие unit/integration/source-boundary tests.

Не должны изменяться: `Player`, `QuestService`, `QuestState`,
`QuestStateList`, `QuestEngine`, `MonsterHunt`, client packet handlers,
`PlayerQuestListDAO`, SQL/schema, quest/game XML, Cool data и command grants.
Проверенный diff не затрагивает перечисленные центральные boundaries.

## 13. Automatic test scope

Минимум:

1. chosen goal -> `CHOSEN_WAITING_ACCEPTANCE` без journal mutation;
2. штатно подготовленный START snapshot -> ACTIVE;
3. progress 0/3 -> 1/3 -> 2/3 -> 3/3;
4. duplicate snapshot/events не создают duplicate transition/chat;
5. 3/3 START -> READY_TO_TURN_IN;
6. REWARD остаётся turn-in state без AI reward;
7. COMPLETE определяется только native status;
8. ранее active, затем null -> ABANDONED;
9. null до acceptance остаётся WAITING, не ABANDONED;
10. `goal clear` не вызывает `QuestService.abandonQuest` и не меняет state;
11. dismiss/logout/map/instance change очищают tracker/manifest и observer;
12. stale template/allowlist/session/role fail-closed;
13. active accepted 1102 до `today` может быть reattached; COMPLETE не
    re-offer-ится;
14. restart не создаёт automatic session и не выдумывает progress;
15. quest target authorization success только для exact approved spawn;
16. no active quest, wrong target ID, walker/temporary/event spawn, wrong
    map/instance, invisible/out-of-range/no-LoS target rejected;
17. progress 3/3, REWARD, COMPLETE, ABANDONED, STALE rejected;
18. legacy exact 210115 сохраняет поведение 2C;
19. один owner event -> не более одного quest-target damage/presentation;
20. duplicate event -> no damage/presentation;
21. companion lethal hit -> один owner `QuestEngine.onKill` и один var increment;
22. owner lethal hit при companion contribution -> один owner increment;
23. no duplicate XP/loot/quest reward contribution;
24. ordinary solo/group quest без companion не меняется;
25. stage 2B `today/choose/status/clear` read-only guarantees;
26. stage 2C trigger/attribution/presentation/death cleanup guarantees;
27. tracker не вызывает QuestState setters, QuestService mutation, packets,
    dialog handlers, DAO/player save;
28. нет отдельного scheduler/thread/future;
29. companion `AionConnection == null`; нет fake connection/CM simulation;
30. source-boundary tests запрещают новые quest core, persistence и game-data
    dependencies.

Tracker покрыт read-only snapshot test doubles без DB. Credit/reward safety
проверяется совместно: unit regression принятого contribution seam объединяет
owner+companion damage в один owner key, а source-boundary tests доказывают
ровно один штатный `QuestEngine.onKill` в solo/team reward paths и отсутствие
второго AI hook, shadow increment, reward/loot mutation. Это не заменяет
ручную проверку реального kill quest 1102.

## 14. Manual acceptance двумя клиентами

Client A — администратор/наблюдатель. Client B — Calmer, access level 0 и exact
persistent grant `companion`.

### Preflight

1. Read-only SQL snapshot Calmer/Cool: account activation/access, players,
   position/online, XP/DP/HP/MP, inventory, quests/effects/cooldowns и grant.
2. Если 1101 не COMPLETE, Calmer обычным клиентом принимает его у Elpas
   `203049` и сдаёт Mires `203057`. Не применять SQL/admin completion.
3. Подтвердить SQL: 1101 COMPLETE, 1102 отсутствует; Cool не изменён.
4. Зафиксировать natural-death как deferred; Cool не менять и `//damage` не
   использовать. Это не блокирует локальную проверку по решению владельца.
5. Включить только утверждённые 2D flags/allowlist 1102; перезапустить
   game-server штатным способом.

### Scenario

1. B: `//companion summon`; A видит одного `[AI]Cool`, без duplicate/ghost.
2. B: `//companion today`; предложение exact 1102 и Mires/targets, journal не
   изменён.
3. B: `//companion goal choose`; status WAITING_ACCEPTANCE; read-only SQL всё
   ещё не содержит 1102.
4. B штатно принимает 1102 у Mires настоящим dialog. Tracker один раз сообщает
   ACTIVE 0/3; A не получает ложных quest packets.
5. B: `//companion assist on`; follow остаётся штатным.
6. B атакует только non-walker 210133/210134. Для каждого kill проверить
   реальные attack animation/damage/aggro и ровно 1/3, 2/3, 3/3.
7. Хотя бы один lethal hit наносит companion. Проверить один quest increment,
   отсутствие позднего/второго hit и отсутствие companion native reward.
8. После 3/3 tracker один раз сообщает READY_TO_TURN_IN; следующие quest
   targets rejected, legacy 210115 regression остаётся штатным.
9. B возвращается к Mires и самостоятельно сдаёт квест. Проверить ровно один
   reward 400 kinah + 180 XP с учётом server rates и native UI; tracker один
   раз сообщает COMPLETE.
10. `goal status` отражает COMPLETE; `goal clear` не меняет journal.
11. Dismiss/re-summon, выход/возврат в visibility и второй client observer не
    создают duplicate/ghost.
12. Smoke: follow/stay/blocked (2A), today/choose read-only (2B), assist exact
    210115/presentation/attribution (2C).

### Postflight

1. SQL Calmer: 1102 COMPLETE/count 1, reward/vars/timestamp согласованы с
   native flow; XP/kinah изменены один раз, inventory только штатным loot.
2. SQL Cool/Test полностью совпадает с baseline для name/position/world,
   online, EXP/DP, HP/MP, inventory, quests, effects, cooldowns; activated=0.
3. Logs не содержат quest mutation от AI, DAO save Cool, duplicate credit,
   unhandled exception или retained tracker/observer.
4. Grant Calmer **не удалять** после local test; отметить review/removal gate
   перед production.

### 14.1. Результат ручной приёмки 2026-08-09

Владелец проекта выполнил сценарий в реальном клиенте и принял
этап 2D. Подтверждено:

- native quest 1102 достиг прогресса `3/3`, а tracker сообщил
  `READY_TO_TURN_IN` и указал Mires `203057` как следующий шаг;
- owner самостоятельно сдал quest штатным диалогом и получил
  штатную награду 400 kinah и 180 XP;
- journal перешёл в native `COMPLETE`, runtime tracker сообщил
  `COMPLETE`, а повторный offer не создаётся;
- postflight consistent read-only SQL snapshot показал у Calmer
  `1102 COMPLETE, completeCount=1`, а также сохранённые штатные
  quest states 1000/1100/1205;
- runtime combat/quest tracking не сохранил данные в Cool/Test:
  account `Test` остался `activated=0`, а у Cool не изменились
  EXP/DP, inventory, effects, cooldowns и journal;
- accepted follow/stay, legacy combat и dismiss/visibility lifecycle не
  показали регрессий или duplicate/ghost.

Таким образом, критерии готовности этого узкого execution slice
выполнены. Deferred natural-death gate и production review persistent
grant остаются ограничениями более широкого rollout, но не блокируют
приёмку 2D.

## 15. Наблюдаемость и profiling

Structured logs без свободного текста и без секретов:

```text
AI_COMPANION_QUEST_EXECUTION action=TRACK result=STATE_CHANGED
  ownerObjectId=... companionObjectId=... companionSessionId=...
  questId=1102 previousState=... newState=... progress=1 required=3
  reason=NATIVE_QUEST_ACTIVE persistence=false

AI_COMPANION_QUEST_EXECUTION action=COMBAT_AUTHORIZATION
  result=ALLOWED|REJECTED questId=1102
  targetTemplateId=210133 reason=... eventId=... persistence=false
```

Status/counters: polls, deduplicated notifications, last poll age, last
state/reason, one-hit result и scheduler state. Structured authorization logs
contain accept/reject reason. Profile 0, 1 и в будущем 10/20 AI только после
расширения one-companion scope; 2D acceptance проверяет, что один 1-second
lookup не заметно меняет scheduler budget misses.

## 16. Risks и blockers

| Риск | Мера |
|---|---|
| Calmer сейчас ineligible | Штатно завершить 1101 клиентом; никаких SQL/admin fixes |
| Quest event API неполон | Budgeted read-only QuestState polling |
| Chat spam | Semantic fingerprint; message only on transition |
| ABANDONED неоднозначен после restart | Только within-session history; no auto recovery |
| Double quest credit | Никаких manual onKill/shadow counters; regression exact call counts |
| Wide NPC allowlist | Quest ID config + immutable objective/spawn manifest |
| Same template elsewhere/dynamic entity | Exact Poeta map, spawn provenance/fingerprint, reject temporary/event/walker |
| Goal selected, quest not accepted | WAITING; quest combat disabled |
| 3/3 status ещё START | READY derived from var0; combat disabled at required count |
| Owner completes/abandons between poll and hit | Re-read exact QuestState inside combat authorization, не полагаться только на cached tracker state |
| Config disabled during combat | All flags checked at hit preflight; session cleared next tick |
| Cool death cleanup not manually proven | Deferred для local 2D; existing early-death tests retained; обязательный gate перед production/сильным контентом |
| Ordinary group/reward regression | Solo/group/third-party regression tests |
| Persistent local grant escapes to production | Explicit deployment checklist review/removal; no other grants |
| Central quest code becomes necessary | Stop before change; separate ADR/approval |

Архитектурных blockers при реализации не обнаружено. Перед manual flow Calmer
должен штатно завершить 1101; deferred death локальную проверку 1102 не
блокирует.

## 17. Проверки реализации

До документации выполнен полный Maven reactor тем же container/JDK process:

```text
maven:3-eclipse-temurin-25
mvn -B -T 4 -Dmaven.source.skip=true -Dmaven.test.skip=false test
BUILD SUCCESS
game-server 121, commons 1; total 122; failures/errors/skips 0
```

После реализации выполнены:

```text
narrow 2C/2D regression: 60 tests, failures/errors/skips 0
all services/ai tests:   129 tests, failures/errors/skips 0
full Maven reactor:      BUILD SUCCESS
  game-server:           144 tests
  commons:                 1 test
  total:                 145 tests, failures/errors/skips 0
git diff --check:        PASS
relative Markdown links: PASS (0 broken)
source-boundary checks:  PASS
```

Полный reactor использовал baseline image `maven:3-eclipse-temurin-25`:

```text
mvn -B -T 4 -Dmaven.source.skip=true -Dmaven.test.skip=false test
```

Проверенный diff не содержит central quest core, SQL/schema или game-data
изменений. Результат не commit-ился.

## 18. Запрещённые расширения первого slice

Не входят: automatic accept/dialog/turn-in, journal admin mutation,
autonomous target search/combat/navigation/pathfinding/teleport, skills,
companion loot/inventory/XP/progression, goal persistence/restart recovery,
PvP, companion group membership, bosses/elites/events/siege/instances,
economy, `CITIZEN_AI`, LLM/chat roleplay, новые SQL/schema/game data.

## 19. Критерии готовности реализации к ручной приёмке

- все flags false by default и fail-closed;
- tracker читает только owner QuestState и не имеет mutation dependencies;
- scheduler/thread count не меняется;
- quest combat проходит только exact active 1102 and approved spawn;
- одна owner ATTACK event даёт максимум один hit и один native progress;
- 3/3, COMPLETE, abandon, clear/dismiss/logout/map change корректны;
- legacy 210115 и ordinary quests/groups не регрессировали;
- full reactor/source-boundary/Markdown/diff checks green;
- Calmer prepared natively; deferred death явно учтён как local limitation;
- two-client checklist and pre/post SQL snapshots выполнены.

## 20. Подтверждённые решения владельца

Владелец подтвердил 2026-08-03:

1. Выбрать budgeted read-only polling `QuestState` вместо добавления quest
   events в центральное ядро.
2. Poll interval 1000 ms, bounded configuration 500..5000 ms, без отдельного
   scheduler/thread/future.
3. Принять предложенную state machine и правило: START + var0 >=3 означает
   READY_TO_TURN_IN, COMPLETE — только настоящий status COMPLETE.
4. Разрешить `today` reattach к уже принятому START/REWARD 1102; COMPLETE
   non-repeatable только сообщается как ALREADY_COMPLETE и не re-offer-ится.
5. `goal clear`/dismiss/logout/map change очищают только runtime tracker и
   никогда не abandon/save owner quest.
6. Config allowlist содержит только quest ID 1102; target IDs/spawns берутся
   из immutable audited execution plan, а walker/dynamic spawns отклоняются.
7. Quest combat — отдельная policy рядом с неизменным legacy 210115 path; live
   QuestState повторно проверяется непосредственно перед hit.
8. Все новые 2D flags false by default; предложенные names и safe bounds.
9. Deferred natural-death item не блокирует код, локальные flags или ручную
   проверку 1102; он обязателен перед production/существенно более опасным
   content. Cool не менять и `//damage` не использовать.
10. Calmer приводится к eligibility только штатным завершением 1101 клиентом;
    persistent `companion` grant сохраняется для local tests и пересматривается
    перед production.

Реализация выполнена в этих границах. После ручной приёмки этапа 2D
ADR-0004 переведён в `Accepted`.
