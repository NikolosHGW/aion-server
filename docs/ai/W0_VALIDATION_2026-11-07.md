# W0: серверная валидация, 2026-11-07

База `3e7585e7263ffc202b7e688814c88b1fab824d68` + W0 diff.
Статус: серверные проверки PASS; **client/gameplay acceptance не выполнена**.
Владелец приостановил игру; ввод/логин в Aion в этом проходе не выполнялись.
[Аудит и границы](W0_IMPLEMENTATION_2026-11-07.md).

## Сборка и unit tests

Java 25, полный reactor `mvn -B -Dmaven.test.skip=false package`: **330 тестов**,
failures/errors/skipped **0/0/0** (commons 1, game-server 324, login-server 5).
Восемь новых W0 unit tests проверяют source/count filters, immutable occurrence,
queue/batch, bounded retry/backoff, read-only status, realm/flags, конфликт и
shutdown barrier при занятом worker. C3 claim fixture и C8 session fixture
сохраняют новый UUID; прежние unit tests зелёные.

## Native MariaDB: два отдельных JVM

Runner: [run_companion_memory_acceptance.py](../../tools/ai/run_companion_memory_acceptance.py).
Probe: [CompanionW0RuntimeProbe](../../game-server/test/com/aionemu/gameserver/services/ai/memory/CompanionW0RuntimeProbe.java).
Новая MariaDB 11.4/tmpfs, localhost 53418, schema `ai_w0_runtime`, сервер 17788.
Рабочие БД и игровые окна не использованы. После PASS контейнер удалён.

| Проверка | Результат |
|---|---|
| default-off status без memory table | PASS, таблица не создаётся неявно |
| Два native Player + два постоянных AI тела на общем host account | Native создание и UUID claim |
| Подготовленный REWARD → настоящий QuestService.finishQuest | Native reward/COMPLETE; повтор finish отказан |
| Production witness для отключённых тестовых акторов | Не создан; fake connection не использовался |
| Explicit witness fixture + native row ещё REWARD | PENDING, проекции нет |
| Native Quest DAO store + committed reader | MATCH, count/time совпадают |
| B confirmed, A durable pending → другой JVM | B читается без тела, A подтверждается worker |
| Event UUID после restart, повтор reconciliation | Прежний ID, ровно две проекции |
| Чужой аккаунт, другой realm | EMPTY |
| Ошибка UPDATE через DB trigger | SQLException, rollback version/attempts; retry PASS |
| Недоступная native quest table | SQLException, pending неизменён, recovery PASS |
| Same key + changed payload/native timestamp | QUARANTINED; confirmed history неизменна |
| 8 доставок через 4 concurrent workers | Один event/version=1 |
| Pending TTL/attempt budget | EXPIRED |
| .987 seconds → native TIMESTAMP(0) | Усечение совпало с normalizer |
| Native shared read lock + concurrent writer | Writer ждёт release |
| Замена claim UUID при прежнем numeric ID | Старая память скрыта, replay rejected, stale pending quarantined |
| Native удаление владельца | Tombstone остаётся, status EMPTY |
| Полные persisted players/inventory/player_quests до/после memory и restart | Неизменны; нет companion quest journal |

REWARD и enqueue witness — явные **test fixtures**, не прохождение квеста клиентом.
Восстановление worker проверено через production runOnce в новом JVM; наблюдение
подключённой пары, UI/пакеты и scheduler с включёнными startup flags остаются
отдельными integration/gameplay пунктами. Прямые SQL изменения probe — только
fault/identity fixtures в одноразовой БД.

Локальные артефакты под workspace `work/`: `w0-build.log`,
`w0-native-20261107-b/report.json`, `exercise.log`, `restore.log`.
Exercise/restore **19.69 / 10.14 s**, обе фазы PASS.
SHA-256 Java sources + runner:
`2c318e3395d6ac7edf25d2e1264a34bdc56c85aa8a2a3675d46357f3c9287a8c`.
SHA-256 server package:
`cf751aaab192922ac20102f2fd31205ed43ced7e573157d805860ab3656bfa69`.
Report фиксирует hashes source/package/logs, исходный HEAD и tracked diff;
source hash включает новые Java-файлы до их первого commit.

## Regression и оставшийся gate

Прежний C8 runner на новой схеме: **9/9 фаз PASS** (fresh-items, items-restart,
combat, combat-restart, navigation, quest, quest-restart, load-cleanup, load-restart).
Проверены native предметы/навыки/движение/quest hooks, отдельные JVM и 0/2/10 тел.
Артефакт: `work/w0-c8-regression-20261107/report.json`, контейнер удалён.
Тот же server package SHA-256, что у W0. Для 2/10 тел p95 прохода scheduler
1194/955 µs; budget misses 3/1 из 120 проходов каждого сценария. Это короткая
проверка, не production capacity/массовый бой. Исторический C8 log не переписан.

В игре ещё проверить: normal turn-in 1102 с двумя owners, positive capture,
отсутствие/смерть/дальность/другой instance спутника, движение/телепорт/dismiss
около сдачи, status после relog/restart и отсутствие чужой памяти. Отдельно —
startup-enabled worker и startup-disabled rollback. C1–C8 не объявляются полностью
принятыми; все новые флаги default-off.

Для zero-loss и неразличимого административного переписывания истории нужен
authoritative occurrence/outbox gateway. Его нет; ограничения перечислены в
[аудите](W0_IMPLEMENTATION_2026-11-07.md).
