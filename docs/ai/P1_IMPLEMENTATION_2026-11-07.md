# P1: подтверждённое прошлое и явный отказ владельца

Дата: 2026-11-07. База: `integration/ai-4.8`, W0 `80094e66d`.
Узкий серверный срез реализован после отдельного разрешения владельца.
[Проверки](P1_VALIDATION_2026-11-07.md) не заменяют клиентскую приёмку.
Общий [Story Partner](COMPANION_STORY_PARTNER_SPEC.md) и ADR-0007…0009 остаются Proposed.

## Проверяемый сценарий

После подтверждённой W0 памяти о сдаче 1102 владелец явно просит совет.
Спутник вспоминает только присутствие при сдаче и предлагает помощь с 1120,
если существующие native проверки и curated manifest допускают её сейчас.
Владелец может отказаться; отказ переживает dismiss/restart и исключает эту
цель из новых `story suggest` и `today` до явного сброса. Владелец может выбрать
её вручную: отказ от рекомендации не запрещает играть.

| Команда | Результат |
|---|---|
| `//companion story status` | Read-only история и последнее решение; работает без вызванного тела |
| `//companion story suggest` | Явный запрос одного предложения; нужны собственный активный постоянный спутник и confirmed память |
| `//companion story decline OfferUUID` | Сохранить отказ от конкретного предложения |
| `//companion story reset OfferUUID` | Разрешить новые предложения; не менять текущее занятие |
| `//companion activity quest 1120` | Прежний явный выбор занятия с повторными native проверками |

UUID берётся из ответа компаньона. Неверный/старый UUID не изменяет новое
предложение. `status` показывает сохранённое предложение, а не обещает, что
квест по-прежнему доступен. Выполнение начинается только прежними явными
командами; диалог не принимает и не сдаёт native квест.

Нет unsolicited/login предложений, LLM, интерпретации свободного текста,
караванов, отношений, политических событий или уведомлений во время боя.
Один отказ не выводит скрытое предпочтение «не любит PvE». Область отказа —
конкретная рекомендация 1120, без срока давности до явного reset.

## Слои и подтверждённые предпосылки

| Слой | Файл / существующая граница |
|---|---|
| Immutable DTO и состояния диалога | [CompanionStory](../../game-server/src/com/aionemu/gameserver/services/ai/story/CompanionStory.java) |
| Политика явного запроса, формулировки, отказ | [CompanionStoryService](../../game-server/src/com/aionemu/gameserver/services/ai/story/CompanionStoryService.java), [store interface](../../game-server/src/com/aionemu/gameserver/services/ai/story/CompanionStoryStore.java) |
| Native owner/session/eligibility adapter | [CompanionService](../../game-server/src/com/aionemu/gameserver/services/ai/CompanionService.java) |
| Owner-only persistence | [CompanionStoryDAO](../../game-server/src/com/aionemu/gameserver/dao/CompanionStoryDAO.java) |
| Командный интерфейс | [Companion](../../game-server/data/handlers/admincommands/Companion.java), прежний доступ владельца уровня 0 |

W0 уже даёт `CONFIRMED`, version 1, stable event UUID и immutable claim UUID.
P1 проверяет realm, текущий account владельца, owner ID, body ID, claim и
PERSONAL_COMPANION binding. PENDING/QUARANTINED/EXPIRED не становятся историей.
Память не доказывает участие во всех убийствах или атомарность наград.

Native source-of-truth для нового предложения: текущие `QuestState`, silent
`ReadOnlyQuestEligibility`, `QuestGoalCandidateProvider` и точный manifest
`executionPlanProvider` из C7. Живой owner/body, отсутствие attack mode/cast,
нужные flags и execution allowlist проверяются перед предложением. Незавершённая
выбранная цель не заменяется новой. Для уже START/REWARD используются прежние
проверки продолжения, без повторного acceptance. При недоступности — объяснение,
без записи нового предложения. В момент ручного выбора действует прежняя
повторная проверка; предложение не резервирует квест или маршрут.

DTO/store не держат ссылки на `Player`; Java сервис предложений не вызывает
movement/combat/quest mutation. `CompanionService` связывает их с текущей
owner session под её lease. Дополнительного scheduler/thread здесь нет.
SQL выполняется только по явной команде, с существующим bounded query timeout;
на movement/combat/event hot path новых запросов нет.

## Сохранение и повторы

Новая [ai_companion_story.sql](../../game-server/sql/ai_companion_story.sql)
согласована с fresh schema `aion_gs.sql` и AI-разделом `update.sql`.
Одна строка `(realm, owner_player_id)`: source event, body/claim, quest1120,
semantic plan hash, offer UUID, decision и revision. Это решение в разговоре,
не новый quest journal и не второй goal intent. Выбранная цель по-прежнему
находится в `ai_companion_goals`, прогресс — только в native journal.

- `OFFERED → DECLINED` сохраняет отказ. Точный повтор не меняет revision.
- `OFFERED/DECLINED → RESET` снимает предложение/отказ; цель остаётся прежней.
- Повторный reset идемпотентен; отложенный decline после reset отклоняется.
- Следующее предложение после reset получает новый UUID. Старый ответ не влияет на него.
- Тот же OFFERED + semantic hash возвращает прежний UUID без записи.
- DECLINED выигрывает у конкурентного повторного предложения.

Перед write отдельный SELECT блокирует W0 source row, затем shared чтение
проверяет claim/binding; собственная строка изменяется в той же транзакции.
Отдельная блокировка source необходима: порядок JOIN optimizer не гарантирует
memory-first. Она сериализует параллельные первые INSERT и ответы для одного
владельца. Native/W0 строки не изменяются. Read-only status использует одну
REPEATABLE_READ snapshot без locking read и без подтверждения W0 pending.

История остаётся при удалении owner: никаких cascade FK, которые позволили бы
при переиспользовании numeric ID незаметно приписать старую память новому телу.
Доступ требует текущую пару/claim. Расхождение source/identity — отказ в операции,
не автоматическое переусыновление. Перенос истории при пересоздании компаньона
не входит в P1.

Ошибка БД не означает «отказ сохранён». При неопределённом подтверждении commit
ответ просит проверить status; повтор остаётся безопасным. `today` при ошибке
чтения предпочтения не выбирает новую цель и не стирает прежнюю. Существующее
старое предложение `today`/chosen intent не очищается отказом: его ручной выбор
остаётся явным решением владельца.

## Flags, rollback и оставшиеся границы

`ai.companions.story.enabled=false` по умолчанию. Нужны также включённые
существующие AI/companion creation/profile/goal persistence и W0 event/memory
flags с неизменным `ai.world_memory.realm`. Для новых предложений дополнительно
нужны activities/goals/execution и разрешённый 1120.

Флаги задаются **при старте**. Rollback: остановить сервер, выключить story flag,
запустить; story не читает таблицу, прежний `today` работает без P1-фильтра.
Данные не удаляются и снова применяются после включения. Отключение W0 также
отключает P1. Конкурентное hot-toggle не объявляется гарантированной границей
уже начавшейся SQL-транзакции. Рабочая игровая БД и клиент в этом проходе не менялись.

Нужен общий игровой gate C1–C8/W0/P1: normal witness, две подключённые пары,
перезапуск с включёнными startup flags, чат/UUID, native выбор и явная смена
занятия. Native тест использует отмеченные fixtures; это не client acceptance.
Клиентский удобный интерфейс вместо длинных команд ещё не сделан.

Унаследованы [ограничения W0](W0_IMPLEMENTATION_2026-11-07.md): возможная потеря
candidate до durable enqueue, отсутствие authoritative occurrence/outbox и
неразличимое привилегированное переписывание native истории в ту же секунду.
Новый универсальный game gateway не создан. Он понадобится перед zero-loss
историей/новыми типами фактов; нынешний P1 не расширяет достоверность W0.

Следующий шаг — автоматизация повторяемых клиентских действий и общий игровой
проход. A1/A2 и более широкие W/S-системы начинаются после соответствующих audits
и gates, согласно [DELIVERY_PLAN](DELIVERY_PLAN.md).
