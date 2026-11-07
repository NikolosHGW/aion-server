# P1: серверная валидация, 2026-11-07

База `80094e66d8f35b0f33fdfaf2a14b05248bbe7e34` + P1 diff.
[Реализация и ограничения](P1_IMPLEMENTATION_2026-11-07.md).
Статус: серверные проверки PASS; **client/gameplay acceptance не выполнена**.
Игровые окна и прежняя БД C8 не использовались; приостановка игры сохранена.

## Сборка

Java 25, полный reactor `mvn -B -Dmaven.test.skip=false package`:
**339 тестов, failures/errors/skipped 0/0/0** (commons 1, game-server 333,
login-server 5). Девять новых [unit tests](../../game-server/test/com/aionemu/gameserver/services/ai/story/CompanionStoryServiceTest.java)
покрывают flags/no-DB, отсутствие выдуманной памяти, readonly/ineligible advice,
сохранённый отказ, stale identity, ошибку чтения, неопределённый commit ACK,
повторную проверку flag перед write и invalid/stale reply.

## Native MariaDB / отдельные JVM

[Runner](../../tools/ai/run_companion_story_acceptance.py) использует Python 3.9+,
Docker с уже доступным `mariadb:11.4`, собранный Java 25 reactor.
Для воспроизведения: `python tools/ai/run_companion_story_acceptance.py --output
<новый-каталог-вне-репозитория> --java <путь-к-java.exe>`.
Фиксированные localhost endpoints 53419/17789, отдельная schema `ai_p1_runtime`,
новый labelled tmpfs container; существующие output/DB не принимаются.
После обеих фаз контейнер остановлен и удалён, runtime password отредактирован.

[Probe](../../game-server/test/com/aionemu/gameserver/services/ai/CompanionP1RuntimeProbe.java)
использует настоящий GameServer, native игроков/создание тел, CompanionService,
native candidate/eligibility, W0 persistence и P1 DAO. Подключённость сессий —
**существующий C8 test seam**. Завершение 1102/witness — **явные test fixtures**;
W0 подтверждается только после native `PlayerQuestListDAO.store`. Fake connection
не создаётся. Здесь не проверены login, пакетный чат и production witness
подключённого игрока. Flags включены fixture после старта; startup-enabled W0
worker остаётся отдельным integration/gameplay пунктом.

| Проверка | Результат |
|---|---|
| Story default-off, таблица временно отсутствует | DISABLED, SQL не нужен |
| Только binding или PENDING W0 | NO_MEMORY, нет новой истории/предложения |
| Confirmed W0 и разрешённый native 1120 | OFFERED с UUID; повтор сохраняет UUID/revision |
| Другой owner/account/realm | Чужая история и ответ отклонены |
| Отказ A, повтор отказа, запрос A/B | A подавлен, B получает собственное предложение; повтор без новой revision |
| Старый `today` после отказа | Не предлагает отвергнутый 1120 |
| Ручной `activity quest 1120` после отказа | Старый goal intent выбран; native квест не принят автоматически |
| Смена на rest | Combat assist выключен; история не запускает действия |
| Reset при выбранной цели | Цель сохранена, новое предложение не вытесняет её |
| Новое предложение после reset | Новый UUID; запоздалый decline старого отклонён |
| Ошибка SQL UPDATE через trigger | UNAVAILABLE, rollback, нет ложного сообщения об успехе |
| Недоступная story table | Явная ошибка; `today` не стирает прежнюю цель |
| 1120 убран из allowlist | Новое предложение не записано |
| P1 выключен | Нет новых решений, legacy `today` работает |
| 8 offer и 8 decline через 4 потока | Один UUID, одна новая revision для всех отказов |
| Подмена claim UUID при прежних numeric IDs | Старая история недоступна, stale body не записывает решение |
| Повторный status | Таблица, UUID/revision не изменены |
| Снимки players/inventory/player_quests/W0 | P1/отказы/fault/retry не меняют native XP/items/kinah/quests и W0 |
| Dismiss и следующий JVM без summon | A DECLINED, B RESET, изоляция и подавление восстановлены |
| Companion native quest journal | Отсутствует |

Последний прогон: `work/p1-native-20261107-b/report.json`, exercise/restore
**20.39 / 11.15 s**, обе фазы PASS. `work/p1-build.log` — полная сборка.
Артефакты лежат в рабочей папке Codex, не в репозитории.
SHA-256 Java sources + P1 runner:
`3ad8e3b2fb0ef38062d8c59348193453bf06dec55f92bff9d62a5866cc47e0a6`.
SHA-256 server package:
`36ac14bccedebfea91424cec7235a4a9e73c0b46816e5b0e4f2eb9612d8ac27e`.
Report хранит hashes исходников, package, logs, исходный HEAD и tracked diff;
source hash включает новые Java-файлы до commit. Doc changes не меняют runtime.

## Regression

Прежний W0 runner на этом же package: exercise/restore **PASS**,
19.31 / 10.49 s, `work/p1-w0-regression-20261107/report.json`.
Проверены прежние native evidence/queue/reconcile/restart/fault/identity границы.
Это прежние W0 fixtures, не новое доказательство клиентского witness.

Прежний C8 runner на том же package: **9/9 фаз PASS** (fresh-items,
items-restart, combat, combat-restart, navigation, quest, quest-restart,
load-cleanup, load-restart). Артефакт:
`work/p1-c8-regression-20261107/report.json`. Проверены предметы, native
навыки/движение/quest hooks, checkpoint/dismiss и отдельные JVM.
Для 0/2/10 тел по 120 scheduler passes; p95 0/1133/987 µs, budget misses
0/3/1. Это короткая регрессия, не production capacity или массовый бой.
Все три одноразовых контейнера после проверок удалены. Старые журналы
валидации и Accepted ADR не изменялись.

## Оставшаяся игровая матрица

1. Два обычных владельца в двух клиентах, настоящий 1102 turn-in с активным
   своим спутником; подтвердить память через штатный worker.
2. `story suggest/status`: видимая история, доступный 1120, копирование UUID
   и decline/reset; повтор/старый ответ не влияют на другого владельца.
3. Смерть/бой/недоступность/завершённый 1120: нет ложного нового предложения.
4. Отказ → другое занятие/rest; ручной выбор 1120 всё ещё доступен согласно native
   правилам, награда и quest journal только у owner.
5. Dismiss, relog, server restart с включёнными flags: UUID/решение/изоляция;
   нет повторного квеста/награды. Startup-disabled rollback с сохранённой таблицей.
6. Общая [матрица C1–C8](COMPANION_ACCEPTANCE_MATRIX.md), W0 positive witness и
   отсутствие памяти при дальности/смерти/другом instance/отсутствии спутника.

Без этих пунктов нет полного product PASS или разрешения production rollout.
Текущий P1 выключен по умолчанию. LLM, новая графическая панель, типы событий
кроме W0, автономные граждане, zero-loss event gateway не реализованы.
