# W0: один подтверждённый факт → личная память компаньона → status после restart

**Исторический проект от 2026-11-06.** W0 отдельно разрешён владельцем 2026-11-07
после серверной части C1–C8. Актуальные решения, аудит и пределы реализации:
[W0_IMPLEMENTATION_2026-11-07](W0_IMPLEMENTATION_2026-11-07.md);
[результаты проверок](W0_VALIDATION_2026-11-07.md). Ниже сохранён исходный проект;
его будущие формулировки не описывают текущий статус разработки.
Дата проекта: 2026-11-06. Проверенная кодовая база: `56ef9b6cdae95ada801c5885ea8f62fecd507fd2`,
ветка `integration/ai-4.8`. На момент этого аудита следующим был C3;
после его серверной реализации следующий шаг кода — **C4**,
см. [текущий статус](PROJECT_STATE.md). На дату исходного проекта W0 не был запущен.
C3–C8/A1–A7 из [DELIVERY_PLAN](DELIVERY_PLAN.md) сохраняются. W0 не требует
строить Living World Engine до полезного компаньона и не включает P1/LLM.
[ADR-0007](ADR-0007-WORLD-EVENTS-AND-MEMORY.md) остаётся Proposed.

## 1. Результат и узкая граница

Кандидат первого события — `OWNER_QUEST_COMPLETION_CONFIRMED` для **квеста 1102**:
владелец законно сдал его при наблюдаемом присутствии своего постоянного
компаньона, native COMPLETE сохранён, отдельная личная память подтверждена.
`//companion status` показывает один факт до и после полного restart:
«Владелец завершил квест 1102; спутник присутствовал при сдаче».

Этот текст допустим только при доказательстве обеих частей. Он не означает
«мы вместе убили всех мобов», не доказывает атомарное сохранение всех наград
и не заменяет native quest journal. Квест 1102 в текущих данных — Elyos/Poeta,
`max_repeat_count=1`; поддержка других квестов, рас и repeatable quests не обещается.
См. [шаблон](../../game-server/data/static_data/quest_data/quest_data.xml#L898).

Не входят: region simulation, spawns, world chronicle всех игроков, relationships,
LLM, память произвольных слухов, автономные граждане/легионы, экономика, PvP/осады,
клиентский UI и изменение native reward flow. Существующие Accepted ADR не расширяются
неявно. Дополнительная XP/reward share компаньону запрещена.

## 2. Что должен закрыть аудит перед первым изменением кода

| Audit | Конкретное решение / доказательство | Stop condition |
|---|---|---|
| Источник | Проследить нормальную сдачу 1102 через `QuestService.finishQuest`, порядок callbacks, все ранние выходы и setter/admin альтернативы. Выбрать одну точку снимка после успешного результата без повторного вызова reward logic | Нельзя отличить законную сдачу от тестового/GM setter или частичного результата |
| Доказательство пары | Проверить immutable owner/body claim, роль PERSONAL_COMPANION, текущее поколение сессии, alive/World/instance/наблюдаемую дистанцию и порядок dismissal/смены цели. Утвердить конкретный критерий присутствия | Нельзя получить согласованный снимок пары в момент события |
| Native сохранение | Проследить `PlayerQuestListDAO`, autosave/logout/shutdown. Подтвердить committed чтение точной строки владельца, согласованность source read → memory commit и гонку с native reset. Нормализовать JVM Timestamp к точности SQL `timestamp`, проверить timezone/driver | Возврат void DAO принимается за успешный commit; неопределённый timestamp/ошибка SQL считается COMPLETE или read/commit race не разрешён |
| Identity/dedupe | Выбрать стабильный source-occurrence key, realm namespace, защиту удаления/reuse player IDs и immutable binding/claim incarnation. Повтор callback не создаёт новое occurrence | Ключ зависит от UUID, runtime session ID или времени повторной обработки |
| Lifecycle и durability | Определить bounded queue, worker, сохранение pending evidence, приём при shutdown и восстановление после logout/restart. Принять явную crash loss window до сохранения candidate | Требуется гарантировать доставку всех событий, но native outbox/commit integration ещё нет |
| Store и безопасность | Спроектировать атомарность только нового memory-домена, UNIQUE/CAS, owner-only query, конфликтные payload, retention, disable barrier и default-off поведение без таблиц | Частичная event/projection запись, чужая память, неограниченный retry или зависимость входа в игру от memory DB |

Это **обязательные узкие W0 audits**, не требование сначала реализовать торговлю,
mixed-faction или осады. Кодовые основания и отдельные поздние audits перечислены
в [интеграционном отчёте](LIVING_WORLD_INTEGRATION_REVIEW_2026-11-06.md).

## 3. Предлагаемый поток данных

1. После штатной сдачи observer получает immutable DTO: owner/body identity,
   generation/claim, quest ID, complete count/time, world/instance, observation time,
   проверенный witness context. На горячем пути нет SQL, HTTP/LLM и сохранённых
   ссылок на изменяемый `Player`. Ошибка memory-наблюдения не меняет исход native сдачи.
2. Общий ограниченный worker сохраняет **pending candidate** в новом memory-домене.
   Это не confirmed world event и ещё не отображаемая память. Хранится минимум
   evidence для одного события, не quest vars, инвентарь или второй quest journal.
3. Новый узкий `NativeQuestCompletionReader` читает committed `player_quests`
   по точному owner + quest ID и сравнивает ожидаемые status/count/time. Ошибки
   чтения возвращаются явно. Нельзя полагаться на DAO, который логирует ошибку и
   возвращает пустой/успешный результат. Read gateway не вызывает native setters,
   `PlayerService.storePlayer`, принудительный save квеста или выдачу наград.
4. Только при совпадении evidence и валидном сохранённом witness context
   `CompanionMemoryDAO` одной транзакцией фиксирует подтверждённый event,
   personal projection, её aggregate position и обработку candidate.
   При конфликте — rollback/quarantine, а не подбор «похожего» события.
5. `//companion status` читает готовую проекцию по авторизованному владельцу.
   Чтение не подтверждает pending, не пишет cursor, не создаёт binding/body,
   не запускает reconciliation. После dismiss/restart факт доступен владельцу
   без нового summon; реализация UI остаётся в существующем текстовом status.
6. При restart worker возобновляет ограниченную обработку **уже сохранённых**
   candidates. Из `binding + COMPLETE` задним числом не восстанавливается
   присутствие спутника. Если свидетельства нет, памяти о совместной сдаче нет.

Шаги 3–4 не должны оставлять непроверенное окно source read → memory commit.
Кандидат решения — согласованное committed чтение native evidence и запись нового
memory-домена в одной короткой DB transaction с проверенной isolation/locking
стратегией. Native строка остаётся read-only; блокировки/тайм-ауты и reset race
обязательны в аудитах и тестах. Исторический факт не переигрывается после admin
reset. При невозможности различить новую инкарнацию/reset источник закрывается;
новый authoritative occurrence gateway становится отдельной предпосылкой.

### Idempotency и порядок

UUID — идентификатор записи, не средство обнаружения повторной native сдачи и
не курсор времени. Стабильный source key выводится из namespace и неизменной
identity owner/source occurrence (1102 + допустимый completion count; точность
времени определяется аудитом). Claim/witness относится к проверенному payload;
повтор наблюдения не должен позволять ту же сдачу записать новому спутнику.
Удаление/повторное создание персонажа требует различимого incarnation, иначе fail-closed.

`UNIQUE(source_key)` + сравнение нормализованного payload: тот же ключ и те же
факты — no-op, иной payload — диагностируемый конфликт. Personal projection имеет
собственную уникальность `(owner, companion claim, event)` и монотонную aggregate
version. Одновременные workers не обходят UNIQUE/CAS. Replay касается только
memory-домена и никогда не вызывает native action/reward повторно.

### Что именно переживает restart

`QuestService.finishQuest` меняет память процесса; `PlayerQuestListDAO` сохраняет
квест отдельно от других player domains. У C2 нет общей транзакции native quest +
world event. Поэтому предлагаемый W0 обещает восстановление **сохранённых pending
и подтверждённой памяти**, но не отсутствие потери наблюдения в любой точке краша.

- Краш до durable candidate: эпизод может отсутствовать. Нельзя достраивать witness.
- Candidate сохранён, native COMPLETE ещё нет: pending, ограниченные retry/TTL;
  при окончательной неопределённости — expire/quarantine, без текста «завершено».
- Native COMPLETE сохранён, candidate есть: безопасная проверка после restart.
- Event/projection commit не завершён: всё откатывается; повтор не создаёт дубль.
- Commit завершён, ack потерян: повтор становится no-op, status остаётся тем же.

Если acceptance потребует **нулевую потерю каждого завершения** даже до durable
candidate, необходим отдельно спроектированный native commit/outbox gateway.
Он ещё отсутствует и не включается незаметно в маленький W0. Это явная незакрытая
предпосылка более сильной гарантии. Присутствие компаньона на shutdown не заменяет
сохранённое witness evidence: cleanup спутников может идти раньше save владельцев.

## 4. Изоляция, ограничение нагрузки и rollback

Owner выбирается из авторизованного caller, не из произвольного параметра команды.
Проверяются immutable binding/claim, роль и актуальность сессии при capture.
После logout/restart runtime lease истекает, но уже сохранённый валидный witness
не аннулируется только из-за нового session ID. Удаление/rebind/конфликт claim
обрабатывается отдельно fail-closed; данные не переходят новому владельцу.
Общий AI-host account не является основанием общей памяти или доступа.

Один общий worker: bounded queue/batch/retry/backoff, deadline/TTL для pending,
явные метрики overflow/lag/conflict/read failure. Нет future/thread на каждого AI.
Перегрузка пропускает новое наблюдение с метрикой, а не блокирует игру и не
объявляет неподтверждённую память успешно сохранённой. Retention payload и личной
проекции проектируется отдельно; TTL candidate не должен молча удалять всю биографию.

**Новые предлагаемые, пока отсутствующие настройки:**

- `ai.world_events.enabled=false`: разрешение capture/confirmation конкретного
  источника; выключение прекращает новые observations и confirmations.
- `ai.world_memory.enabled=false`: разрешение personal projection/display;
  в W0 полный путь включается только при обоих флагах. Частичное включение не
  производит «половину» confirmed event без атомарной personal projection.

Общие AI/product flags продолжают действовать. Отключение в runtime, если будет
поддержано, сериализуется с commit через epoch/barrier: после завершения barrier
нет новых commits старого поколения. Уже начатая короткая транзакция либо
завершается до barrier, либо откатывается. Если конфигурация доступна только
при старте, документировать rollback через штатный restart, не обещать hot reload.
Нужны оба режима проверки. Pending/история при выключении сохраняются, native
таблицы не меняются. Оба флага off должны работать без новых таблиц. DB failure
отключает память с диагностикой, но не старые summon/follow/combat/quest функции.

## 5. Предполагаемые файлы отдельной реализации

Пути ниже — план изменения, **не изменения текущего документального прохода**.
Новые имена предварительные; детали корректируются после gate-audits.

| Файл / область | Узкая работа |
|---|---|
| `game-server/src/com/aionemu/gameserver/services/QuestService.java` | Одна source-specific точка безопасного наблюдения нормальной сдачи 1102, без изменения native reward/quest sequence; точное место после аудита callbacks |
| `game-server/src/com/aionemu/gameserver/services/ai/CompanionService.java` | Согласованный снимок существующей пары/lease; чтение личной памяти в `getStatus`, без записи из status |
| Новые `services/ai/memory/QuestCompletionEvidence.java`, `CompanionEpisodeService.java`, `NativeQuestCompletionReader.java` под тем же Java source root | Immutable evidence, ограниченный reconciler, exception-aware read-only native gateway; без универсальной event bus |
| Новый `game-server/src/com/aionemu/gameserver/dao/CompanionMemoryDAO.java` | Pending и атомарный event + personal projection; stable UNIQUE, owner scope, restart |
| `game-server/src/com/aionemu/gameserver/services/ai/SyntheticPlayerRuntime.java` | Если потребуется: явный lifecycle общего memory worker, без SQL в scheduler tick; hook старта/остановки выбрать аудитом |
| `game-server/src/com/aionemu/gameserver/configs/main/AIConfig.java`, `game-server/config/main/ai.properties` | Два default-off флага и ограниченные параметры worker; не добавлять flags будущих регионов/LLM |
| `game-server/data/handlers/admincommands/Companion.java` | Только при необходимости отобразить результат существующего status; не добавлять произвольный owner selector или команды записи памяти |
| AI DDL в `game-server/sql/aion_gs.sql`, AI-разделе `update.sql`, focused AI SQL | Только схема нового memory-домена; согласовать все копии. Допустимо переписать unreleased AI-определения, не наслаивать экспериментальные миграции. Native таблицы не менять |
| `game-server/test/com/aionemu/gameserver/services/ai/memory/` (новые тесты) | Source/witness, permissions, dedupe/replay, status, flags, bounded retry; ориентироваться на фактическую структуру test source root |
| Новый MariaDB probe/integration test рядом с существующими AI probes | Два процесса JVM, сбои транзакций, source read failure, DB invariants; только отдельная свежая тестовая БД |
| `docs/ai/IMPLEMENTATION_PLAN_W0.md`, отдельный будущий validation log | Утверждённые ограничения и фактические результаты; этот Proposed план сам по себе не validation |

Не добавлять DAO в `CompanionQuestExecutionTracker`: его read-only контракт
ADR-0004 остаётся. Не подменять `Player.isOnline`, не создавать fake connection,
не сохранять native квест/инвентарь из memory service. Отдельный quest/economy
write gateway не нужен для этого W0 и не разрешён его границей.

## 6. Тестовая матрица и критерий завершения

Все перечисленные тесты **предстоит реализовать и выполнить в отдельном W0**.

| Уровень | Сценарии | Ожидаемое |
|---|---|---|
| Source | START/REWARD, native save ещё нет, SQL failure, не 1102, GM setter, другой count/time | Нет confirmed памяти; ошибки не равны отсутствию/успеху |
| Witness | Нет тела, другой world/instance, недостаточная близость, stale lease, death/dismiss race | Нет утверждения о присутствии; native сдача продолжает работать |
| Privacy | Два owner одновременно, общий AI account, подмена body/owner, relog, удаление/reuse identity | Каждый видит только собственный доказанный эпизод; чужие/неопределённые связи отклонены |
| Dedupe | Duplicate callback/new UUID, replay, concurrent workers, same key + changed payload | Одна память; конфликт диагностируется, нет нового native результата |
| SQL atomicity | Сбой между event/projection/cursor, rollback, commit до ack, UNIQUE contention | Нет частичного состояния и дубля после повторения |
| Restart | Все crash windows из §3; два запуска JVM; pending до logout save; сохранённый факт без активного тела | Подтверждённое сохраняется, возможная потеря до durable candidate честно видна; witness не выдумывается |
| Time/source drift | JVM ms vs SQL seconds, timezone, повторное чтение, native reset/изменение evidence | Точное сравнение по принятой нормализации; конфликт fail-closed |
| Read-only | Несколько status до/после dismiss/restart, DB unavailable | Status не пишет event/cursor/candidate/quest и не вызывает reward; unknown показывается явно |
| Native invariants | Снимки XP/items/kinah/native quest до и после replay, retry и toggle | Все memory-операции оставляют эти данные неизменными; нет второй XP share |
| Flags | Все 4 комбинации двух flags, existing AI off, выключение при in-flight, restart без DDL при flags off | Нет новых commits после barrier; нет частичных проекций; старые возможности работают |
| Capacity | Queue overflow, недоступная БД, длительный backlog, лимит batch/retry, остановка | Ограниченные память/работа/latency; native game flow не ждёт DB; пропуски наблюдаемы |
| Gameplay позже | Два настоящих владельца/спутника, обычная сдача 1102, status, dismiss, restart | Изолированная память совпадает с реальным опытом; общая C1/C2 приёмка остаётся отдельной |

Acceptance: один allowlisted event, честный текст, read-only status после restart,
owner isolation, idempotency и documented crash semantics; native DB invariants;
default-off/rollback; серверные проверки и отдельно зафиксированная клиентская
проверка. Не объявлять полную приёмку, пока её соответствующий слой не выполнен.

## 7. Формулировка отдельной задачи

Реализовать W0 на актуальном HEAD после закрытия узких audits: один подтверждённый
native факт сдачи 1102 владельцем в присутствии компаньона, личная память и
read-only status после restart. Соблюсти owner isolation, stable idempotency,
native source-of-truth, default-off flags и rollback. Не реализовывать регионы,
политику, автономную экономику или LLM. Если native evidence/witness нельзя
подтвердить в выбранной границе, остановить этот путь fail-closed и отдельно
описать необходимый gateway, не расширяя реализацию скрыто.
