# W0: реализация, кодовый аудит и границы

База: `integration/ai-4.8`, `3e7585e7263ffc202b7e688814c88b1fab824d68`.
Владелец отдельно разрешил W0 после серверной части C1–C8 и приостановил игровую
приёмку. [Исходный проект](IMPLEMENTATION_PLAN_W0.md) сохранён как проект;
[результаты](W0_VALIDATION_2026-11-07.md) разделяют серверный PASS и открытый игровой gate.
ADR-0007…0009 остаются **Proposed**; принятые ADR и прежние validation logs не изменены.

## Результат и scope

Один эпизод для квеста **1102**, Elyos/Poeta, первая сдача через
`QuestService.finishQuest`, NPC **203057**, `SELECTED_QUEST_NOREWARD`.
Текст: «Владелец завершил квест 1102; спутник присутствовал при сдаче».
Он не утверждает участие во всех убийствах или атомарное сохранение наград.
Источник истины по квесту — native `player_quests`.

Вне W0: повторяемые/другие квесты, полная биография, отношения/P1, универсальная
event bus, LLM, регионы, автономные жители/легионы, экономика, межфракционные
правила, осады, клиентский UI и автоматизация логина. Нет fake connection,
второго quest journal, дополнительной XP/reward share или offline farm.
C1–C8/A1–A7 сохраняются; Living World Engine не стал их предпосылкой.

## Что подтвердилось кодом

| Audit | Основание и принятое узкое решение |
|---|---|
| Native source | [QuestService.finishQuest](../../game-server/src/com/aionemu/gameserver/services/QuestService.java) проверяет REWARD, выдаёт награды, ставит COMPLETE, вызывает callbacks. Hook добавлен только в успешном конце; previous count снят до изменения. Reward flow не переставлен и не повторён |
| Admin/setter alternatives | [Quest handler](../../game-server/data/handlers/admincommands/Quest.java) напрямую меняет статус и вызывает quest callback. Поэтому `onQuestCompleted` не источник W0. Нужны finishQuest, previous count 0, count 1, правильные quest/NPC/dialog; GM-владелец исключён |
| Native data | [Poeta](../../game-server/data/static_data/quest_script_data/poeta.xml) связывает MonsterHunt 1102 с 203057; native quest data задаёт max repeat 1. Другие квесты требуют отдельного аудита |
| Native save | [PlayerQuestListDAO](../../game-server/src/com/aionemu/gameserver/dao/PlayerQuestListDAO.java) сохраняет квест отдельно от наград, ошибки SQL логирует, return — void. Его возврат не является доказательством commit |
| Ownership incarnation | В исходном claim нет incarnation ID: имя тела детерминировано owner ID, created_at секундный. В unreleased AI schema добавлен UUID `claim_token`, замороженный в session при summon. Native players schema не менялся |
| Source read → commit | [NativeQuestCompletionReader](../../game-server/src/com/aionemu/gameserver/services/ai/memory/NativeQuestCompletionReader.java) читает committed claim/binding и quest status/count/time, удерживая shared locks до memory commit в той же READ COMMITTED транзакции. Ошибка SQL явно прерывает её |
| Personal status | SELECT по caller owner ID, его account ID, текущим claim/body/binding. Общий AI-host account не даёт общей памяти. Чтение не создаёт binding/body и не выполняет reconciliation |

## Capture и подтверждение

[CompanionService](../../game-server/src/com/aionemu/gameserver/services/ai/CompanionService.java)
берёт exact owner lease через try-lock: занятый lease пропускается без ожидания.
Проверяются current session, роль COMPANION, registry/World identity, обычный
подключённый владелец, отсутствие client connection у тела, alive/spawned,
Poeta/common instance, ≤10 м до владельца, native visibility и line of sight.
Владелец также должен находиться ≤10 м от правильного spawned native NPC.

Lease сериализует companion commands/cleanup, но не owner movement. Поэтому
позиции считываются до/после: изменение во время наблюдения приводит к пропуску.
Перед enqueue повторяются usable/active/alive/spawned/connection. Это короткое
наблюдение присутствия, не evidence всего боя. Positive capture в игре ещё не принят.
В очереди только immutable DTO, без Player/QuestState. На горячем пути нет SQL.
Ошибка наблюдения не меняет результат native сдачи.

Один worker сохраняет PENDING, затем проверяет native committed COMPLETE/count/time
и текущий claim/binding. W0 использует **одну memory row** для candidate, event UUID
и единственной personal projection. Транзакционный переход version 0 → 1,
CHECK и UNIQUE исключают частичную event/projection запись. Отдельные chronicle/cursor
таблицы для этого среза не нужны; это осознанное сужение исходного предложения.
После dismiss/restart факт читается без summon. Новый runtime generation не
аннулирует прошлое, но другой persistent claim/body запрещает его наследование.

## Dedupe, timestamp и reset

Source key — `(stable realm, numeric owner ID, quest 1102)`: **один эпизод за всю
жизнь ключа**. Event UUID не dedupe key. Тот же payload — no-op; конфликт pending —
quarantine; конфликт confirmed не переписывает историю. После reset второй event
не создаётся. Tombstones намеренно без cascading FK: удаление владельца не
освобождает source key, новый claim не получает старую память. Политика дальнейшей
очистки пока не спроектирована; чистить tombstones/менять realm ради reset нельзя.

Native `complete_time` имеет секундную точность. JVM Timestamp нормализуется вниз
до секунды. На MariaDB 11.4/JDBC с `serverTimezone=Europe/Moscow` проверены actual
native DAO roundtrip и .987 секунды. Другой SQL mode/driver/timezone требует прогона;
несовпадение даёт quarantine. Native writer реально ждёт shared lock до commit/rollback.

**Предел доказательства:** native таблица не хранит immutable occurrence ID.
Привилегированная перезапись истории, восстановление БД или delete/recreate квеста
с теми же count и секундой (ABA) неотличимы по этим данным. W0 не поддерживает это
как безопасный recovery/reset-протокол; нельзя переносить прежние pending в такую
историю и считать совпадение колонок доказательством. Нужен отдельный authoritative
occurrence/outbox gateway. Он **не реализован** и остаётся предпосылкой расширенного
recovery/production гарантий. Обычное несовпадение count/time/claim закрывается
fail-closed. GM-подготовленный REWARD обычному игроку не доказывает честные предыдущие
убийства; текст памяти их и не утверждает.

## Durability, лимиты и rollback

| Ситуация | Поведение |
|---|---|
| Краш/остановка до durable candidate | Наблюдение может потеряться; witness задним числом не достраивается |
| Candidate есть, native COMPLETE нет | PENDING, bounded retry/TTL |
| Candidate и native COMPLETE сохранены | Worker может подтвердить после restart |
| Ошибка SQL | Rollback/backoff, без подтверждения |
| Commit прошёл, ответ потерян | Replay не создаёт второй event и не выдаёт награды |
| Конфликт identity/source/payload | Quarantine/отказ, без подбора похожего события |

Queue 128; до 8 saves и 8 reconciliations за проход; один daemon worker, пауза
1 секунда. До 3 попыток сохранить queued candidate, DB-error backoff 30 секунд.
Durable pending: до 256 native checks, интервал от 10 до 300 секунд, TTL 24 часа.
Query timeout 2 секунды, ожидание connection — дополнительно существующий pool
timeout. Worker не строгий real-time scheduler. Queue/persisted/dropped/failures/
conflicts доступны через административный общий status; lag пока отдельно не измеряется.

```properties
ai.world_events.enabled = false
ai.world_memory.enabled = false
ai.world_memory.realm =
```

Нужны оба флага, существующие AI/synthetic/companion/creation/profile flags и
стабильный realm `[a-zA-Z0-9_-]{1,48}`. Без realm путь закрыт. Настройки startup-only,
hot reload не обещается. Rollback: штатная остановка → оба flags off → restart.
`close()` прекращает приём и ждёт текущую worker transaction; после возврата
barrier новых commit нет. Transient queue сбрасывается с учётом потерь, durable
pending/история остаются. Оба flags off работают без memory DDL.
GameServer start/ShutdownHook stop отделены от AI ticks; cleanup спутников и
native saves не зависят от успешного memory shutdown.

## Схема и оставшиеся gates

Согласованы `aion_gs.sql`, `update.sql`, `ai_companion_creation.sql`; новая memory
таблица также в [ai_companion_memory.sql](../../game-server/sql/ai_companion_memory.sql).
Используем свежую dev DB по решению владельца. `CREATE IF NOT EXISTS` не добавит
token в старую таблицу: старый экспериментальный runtime нельзя обновлять только
JAR без подготовки новой схемы. Рабочая БД в этом проходе не менялась.

Нулевая потеря до durable candidate, универсальный occurrence/recovery gateway,
атомарная quest+rewards+event транзакция и real-client witness пока отсутствуют.
Factions/legions/PvP/siege gateways не расширялись. Default-off до общей приёмки.
Следующий узкий P1 — чтение доказанного прошлого и сохранение отказа от предложения
без LLM/наград/world simulation. Перед A1/A2 остаётся общий игровой gate C1–C8 + W0/P1.
