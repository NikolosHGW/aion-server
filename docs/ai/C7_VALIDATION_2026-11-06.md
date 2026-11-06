# C7 — серверная проверка

Дата: 2026-11-06. База `9001c3b90`. Это серверная проверка, не клиентская приёмка.

- Offline Maven reactor package: **320 tests, 0 failures/errors/skips**;
  завершён 23:46:14 MSK. Проверена также компиляция динамической команды Companion.
- Новые тесты: malformed/duplicate/unknown allowlist, revocation через subset,
  exact static targets, запрет random walking и подмены координат, подсказки
  acceptance/progress/turn-in/completion, независимые native readers владельцев,
  отсутствие прогресса от повторного status, новый manifest и подавление повторов.
- `CompanionC7RuntimeProbe exercise`: PASS 23:47:05 MSK. Реальные данные сервера
  подтвердили 1120: MonsterHunt/var0/9/11 точек, и неизменный 1102: 39 точек.
  Native QuestService принял задание владельца; checked quest combat разрешил настоящую
  цель в разрешённой точке и запретил её после отзыва allowlist/flag/clear.
  Четыре события, введённые **на границе native QuestEngine.onKill**, дали 4/9;
  это тест native quest hook, а не доказательство четырёх убийств в клиенте.
  Чужой владелец и тело спутника не получили журнал/прогресс.
- `restore`: PASS 23:47:53 MSK, отдельная JVM. Из SQL восстановились intent 1120,
  его semantic fingerprint и настоящий счётчик 4/9. Второй журнал не создаётся.

Использована только временная MariaDB C5 `ai_c5_runtime`, localhost:53413,
с guard в probe. Рабочая БД, клиент и сервер пользователя не менялись.
Локальные журналы: workspace `work/c7-build.log` и
`work/c5-validation-20261106/c7-native-{exercise,restore}.log`.
Повторяемые исходники probe и тестов входят в Git; локальные логи — нет.

Остаются клиентские проверки: выбор занятий обычным аккаунтом, отображение текста
и координат, native acceptance/9 реальных kills/сдача с выбором награды, переключение
занятия во время каста, два владельца и relog. C8 собирает эти пункты в общую матрицу.
Полностью пройденным продуктовый сценарий до этой приёмки не объявляется.
