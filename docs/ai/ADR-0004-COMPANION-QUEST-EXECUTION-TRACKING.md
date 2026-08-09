# ADR-0004: tracking выполнения квеста персональным companion

Статус: Accepted

Дата: 2026-08-03

Связанные решения:

- [ADR-0001](ADR-0001-SERVER-CONTROLLED-PLAYER.md) — fully loaded `Player`
  без `AionConnection` остаётся единственным телом AI в `World`;
- [ADR-0002](ADR-0002-READ-ONLY-QUEST-GOAL-PLANNING.md) — `today` и
  `goal choose` только планируют runtime goal и не меняют journal;
- [ADR-0003](ADR-0003-SERVER-CONTROLLED-COMBAT-ACTIONS.md) — один настоящий
  owner attack event может вызвать один checked basic hit companion, а final
  contribution отображается на owner;
- подробный аудит и предлагаемый scope —
  [IMPLEMENTATION_PLAN_2D](IMPLEMENTATION_PLAN_2D.md).

## Контекст

Первый execution slice ограничен Elyos quest `1102` (`Kerubar Hunt`) в Poeta.
Владелец сам принимает и сдаёт квест через штатный клиентский диалог. Companion
должен только заметить настоящий `QuestState`, показать фактический progress и
разрешить уже принятый combat path 2C для текущих kill targets.

Настоящий journal владельца должен оставаться единственным источником истины.
Tracking запрещено вызывать `QuestEngine.onDialog()`,
`QuestService.startQuest()`, `QuestService.finishQuest()`,
`QuestService.abandonQuest()`, setters `QuestState`, item/reward services,
quest packets или DAO save.

Текущий observer API не предоставляет полного read-only quest lifecycle:

- [`ObserverType`](../../game-server/src/com/aionemu/gameserver/controllers/observer/ObserverType.java)
  не содержит quest start/update/abandon/complete событий;
- [`QuestEngine.onQuestCompleted()`](../../game-server/src/com/aionemu/gameserver/questEngine/QuestEngine.java)
  уведомляет зарегистрированные quest handlers только после COMPLETE, но не
  является общим subscriber API и не покрывает acceptance, progress или
  abandon;
- [`QuestState`](../../game-server/src/com/aionemu/gameserver/questEngine/model/QuestState.java)
  не публикует изменения, а его setters одновременно помечают данные для
  persistence.

Добавление событий в центральный quest engine дало бы полную событийность, но
расширило бы риск и integration surface ради одного O(1) lookup.

## Решение, подтверждённое для реализации

1. Создать runtime-only `CompanionQuestExecutionSession`, принадлежащую exact
   связке owner object ID, companion object ID, companion session ID и immutable
   выбранному execution plan.
2. Использовать budgeted read-only tracking. Существующий
   [`SyntheticPlayerScheduler`](../../game-server/src/com/aionemu/gameserver/services/ai/SyntheticPlayerScheduler.java)
   продолжает вызывать один `CompanionController.tick()`. Quest tracker не чаще
   одного раза в 1000 ms читает один `QuestState` по ID из `QuestStateList`.
   Новый scheduler, thread и future не создаются.
3. Состояние вычислять из immutable plan и read-only snapshot
   `(questState present, status, var0, completeCount, owner/map/session
   invariants)`. Tracker не меняет `QuestState` и не считает kills отдельно.
4. Дедуплицировать уведомления по semantic fingerprint
   `(executionState, clampedProgress, nextStep, reason)`. Один и тот же snapshot
   не создаёт повторный chat message на каждом tick.
5. После `goal choose` состояние — `CHOSEN_WAITING_ACCEPTANCE`. Появление
   штатного `QuestState(1102, START)` автоматически переводит session в
   `ACTIVE_OBJECTIVE`. Для старого-style `MonsterHunt` значение `var0 == 3`
   означает `READY_TO_TURN_IN`, хотя статус до диалога с Mires остаётся START.
   Только настоящий `QuestStatus.COMPLETE` означает `COMPLETED`.
6. Исчезновение ранее наблюдавшегося START/REWARD state в той же runtime
   session означает `ABANDONED`. До первого acceptance отсутствие state
   означает `CHOSEN_WAITING_ACCEPTANCE`. После restart история не
   восстанавливается автоматически.
7. `goal clear`, dismiss, owner logout/map change и shutdown удаляют только
   execution session. Настоящий quest остаётся в journal и сохраняется
   обычным player persistence.
8. Если 1102 уже START/REWARD до `today`, read-only planning может построить
   тот же audited execution plan и после `choose` немедленно присоединиться к
   фактическому state. Если 1102 COMPLETE и non-repeatable, `today` сообщает
   `ALREADY_COMPLETE` и не создаёт новое offer. Автовосстановления без команды
   пока нет.
9. Разрешение quest combat оформляется отдельной fail-closed
   `QuestCombatAuthorizationPolicy`. Оно является дополнительной веткой к
   exact policy 2C для NPC `210115`, а не расширением глобального NPC
   allowlist.
10. Quest policy разрешает `210133/210134` только для exact active execution
    session, owner, role, configured quest `1102`, `QuestStatus.START`,
    `var0 < 3`, карты Poeta `210010000`, той же instance, настоящего owner
    ATTACK event и прошедших штатных hostility/KnownList/visibility/range/LoS
    проверок. Target обязан совпасть с immutable манифестом проверенных
    non-temporary, non-event, non-walker static spawns.
11. Target IDs и spawn fingerprints извлекаются из проверенных quest/static
    data при построении immutable execution plan. Их нельзя независимо
    дублировать во втором configuration allowlist. Config содержит только
    разрешённый quest ID `1102` и отдельные flags со значением `false` по
    умолчанию.
12. Damage, presentation и cadence остаются в принятом checked path 2C:
    `PlayerController.attackTarget(target, 0, false)`. Tracker и policy не
    наносят damage, не меняют HP, не вызывают `CM_ATTACK` и не создают packets
    от имени companion.

## Runtime state machine

```text
NO_GOAL
  -> OFFERED                         // today, read-only
  -> CHOSEN_WAITING_ACCEPTANCE       // goal choose; journal не изменён
  -> ACTIVE_OBJECTIVE (0/3..2/3)     // owner штатно принял 1102
  -> READY_TO_TURN_IN (3/3, START)   // цель выполнена
  -> COMPLETED                       // owner штатно получил reward

ACTIVE_OBJECTIVE|READY_TO_TURN_IN
  -> ABANDONED                       // ранее видимый state исчез в той же session

любое runtime state
  -> STALE                           // semantic plan/template/session mismatch
  -> BLOCKED                         // runtime invariant не позволяет продолжать
  -> NO_GOAL                         // clear/dismiss/logout/map change/shutdown
```

`OFFERED` и `CHOSEN_WAITING_ACCEPTANCE` сохраняют семантику этапа 2B.
`BLOCKED` не заменяет удаление companion при owner map/instance change:
существующий lifecycle сначала dismiss-ит body и вместе с ним очищает goal.

## Почему budgeted polling, а не событие

### A. Event-driven tracking

Преимущества: немедленная реакция; нет периодического lookup.

Недостатки и риски: полного существующего события нет. Completion callback
покрывает только конец и предназначен для quest handlers. Для start, progress,
abandon потребовались бы новые hooks во всех mutation paths либо observer в
центральных `QuestService`, `QuestState` и `QuestStateList`, с attach/detach и
duplicate semantics. Для минимального 2D это слишком широкое изменение.

### B. Budgeted read-only tracking — рекомендуется

Преимущества: один O(1) map lookup; реальный `QuestState` — единственная
истина; reuse общего scheduler; легко проверить отсутствие side effects и chat
spam; не меняется ordinary quest flow.

Недостатки: переход замечается с задержкой до polling interval. Tracker должен
помнить, видел ли он активный state, чтобы отличить ожидание acceptance от
abandon в текущей session.

### C. Shadow quest progress

Преимущества: companion сразу знает о combat signal.

Недостатки: расходится с journal при group credit, чужом lethal hit, отказе
QuestEngine, abandon/restart и любом будущем handler rule. Появляется второй
источник истины. Вариант отклонён.

## Quest credit и reward boundary

Принятый [`CombatContributionOwnerResolver`](../../game-server/src/com/aionemu/gameserver/services/ai/combat/CombatContributionOwnerResolver.java)
проецирует raw companion на owner при построении
[`DamageList`](../../game-server/src/com/aionemu/gameserver/controllers/attack/DamageList.java).
Owner и companion damage попадают в одну `DamageInfo(owner)` строку.
[`NpcController.doReward()`](../../game-server/src/com/aionemu/gameserver/controllers/NpcController.java)
вызывает `QuestEngine.onKill()` один раз для этой solo owner contribution;
[`PlayerTeamDistributionService`](../../game-server/src/com/aionemu/gameserver/model/team/common/service/PlayerTeamDistributionService.java)
в team flow вызывает его один раз на каждого допустимого участника, включая
owner ровно один раз. Lethal blow не создаёт второй reward pipeline: решающим
является агрегированный final damage list.

Поэтому и когда последний удар делает owner, и когда его делает companion:

- physical damage/threat source остаётся companion там, где он ударил;
- owner contribution включает обе raw строки ровно один раз;
- `MonsterHunt.onKillEvent()` получает owner и увеличивает настоящий `var0`
  максимум на единицу за один `QuestEngine.onKill()`;
- companion не имеет отдельной reward contribution и не получает native
  XP/DP/loot/quest reward;
- `QuestService.finishQuest()` выдаёт owner 400 kinah и 180 XP только после
  штатного turn-in.

Это доказательство зависит от сохранения attribution invariants ADR-0003 и
обязательных regression tests. Tracker сам не участвует в credit/reward.

## Последствия и ограничения

- Реакция UI не мгновенная, а bounded polling interval.
- Runtime goal не переживает dismiss/restart; настоящий owner quest переживает
  их через штатный `PlayerQuestListDAO`.
- `ABANDONED` достоверно различим только после наблюдённого active state в той
  же execution session. После restart null state не доказывает abandon.
- Первый slice знает только exact audited shape quest 1102. Skills,
  autonomous combat/navigation, PvP, groups companion, bosses, elites, events,
  siege, instances, looting companion, progression, economy, persistence goal
  и `CITIZEN_AI` остаются вне решения.
- Natural death companion на `210133/210134` не доказана вручную. По явному
  решению владельца от 2026-08-03 это не блокирует Java-реализацию, локальное
  включение flags и ручную проверку 1102. Cool/HP/regen/DB-state не меняются,
  `//damage` не используется. Тест обязателен перед public/production rollout
  и перед более сильными NPC, сложными quest, instances, bosses и siege.

## Rollback

Откат 2D — выключить отдельные quest-execution и quest-combat flags, очистить
runtime execution session и оставить принятые 2A/2B/2C без изменений. Quest
1102 владельца не удаляется и не изменяется rollback-ом. После снятия кода
данные БД и game data не требуют миграции, потому что новых schema/data и
persistence AI goal нет.

## Статус решения

Владелец подтвердил решения для реализации 2026-08-03, а 2026-08-09
принял этап 2D после ручной проверки настоящего quest 1102 в клиенте и
consistent read-only postflight snapshot. Подтверждены native progress
3/3, штатные turn-in/reward/COMPLETE, runtime-only tracking и отсутствие
persistence в template Cool/Test. Решение переведено в `Accepted`.

Фактический scope, полные результаты приёмки и принятое deferred-death
ограничение зафиксированы в
[плане 2D](IMPLEMENTATION_PLAN_2D.md#141-результат-ручной-приёмки-2026-08-09).
