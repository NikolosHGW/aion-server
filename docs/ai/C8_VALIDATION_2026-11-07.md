# C8 — серверная приёмка, 2026-11-07

База C7 `b4415d3ff`. **Серверная часть PASS; клиентская приёмка OPEN.**
[План и повторение](IMPLEMENTATION_PLAN_C8.md),
[матрица игрового прохода](COMPANION_ACCEPTANCE_MATRIX.md),
[машиночитаемый отчёт](validation/c8-server-results-2026-11-07.json).

Полный offline Maven reactor package: **322 tests, 0 failures/errors/skipped**,
2026-11-07 00:08:40 MSK. Новые два теста проверяют учёт долгого последнего действия,
точную границу бюджета, empty pass и сохранение пикового времени.
Повторяемый runner проверил новый SQL schema и девять отдельных JVM:

| Фаза | Результат | Секунды всей JVM |
|---|---|---|
| `fresh-items` | PASS | 20.21 |
| `items-restart` | PASS | 11.14 |
| `combat` | PASS | 32.83 |
| `combat-restart` | PASS | 9.71 |
| `navigation` | PASS | 10.51 |
| `quest` | PASS | 11.2 |
| `quest-restart` | PASS | 10.37 |
| `load-cleanup` | PASS | 61.86 |
| `load-restart` | PASS | 10.83 |

## Что подтверждено

- C3: два независимых владельца/blueprint, классы/уровни/обычные навыки, appearance
  после изменения и штатного удаления source. Нет копирования чужого богатства.
- C5: native equip/instant potion, расход/HP/cooldown, один item ID, две долговечные
  квитанции, параллельный duplicate request, rollback SQL и fence старого экземпляра.
- C4: настоящие heal/offense/первое melee звено, mana/cooldown, cancel и stale
  authorization, отсутствие чужого лечения/второго journal; отдельный restart.
- C6: native geodata/move task/arrival/stay/cancel; disabled geodata и disconnect seam.
- C7: manifests 1102 и 1120, native acceptance/kill-hook 4/9, revoke before hit,
  независимый owner journal и intent/native progress после отдельного restart.
- C8: реальные body/World/registry/движение и реальный CompanionService для
  catalog/quest choice/next/support/rest, точной owner identity и dismiss.
  Искусственный SQL trigger вызывает **именно ожидаемую** ошибку checkpoint:
  тело удалено, cancelled lease удерживает ID, второй владелец продолжает работать;
  повторный production dismiss сохраняет frozen snapshot. Global flag очищает все
  lease, World, registry, movement и scheduler записи. В новой JVM восстановлены
  12 отдельных тел (партии 2 и 10), их класс/уровень; private quest journal пуст.

Connectivity и private session fixture описаны в плане. Это не login/summon packet
test. C7 onKill введён на native hook boundary, не выдаётся за реальные убийства в
клиенте. Награды не начисляются отдельным AI-кодом. Полный logout handover и получение
награды через клиент входят в открытые пункты матрицы.

## Короткая нагрузка

1 действие/проход, бюджет 2 ms, период 200 ms, по 120 проходов (пустой цикл без sleep).
Память — **весь heap игрового сервера**, без GC, не расход одного компаньона.

| Тел | p50, ms | p95, ms | max, ms | Превышений | Heap, MiB |
|---|---|---|---|---|---|
| 0 | 0.000 | 0.000 | 0.019 | 0 | 815.4 |
| 2 | 0.220 | 0.910 | 13.349 | 3 | 862.4 |
| 10 | 0.213 | 0.953 | 1.961 | 0 | 925.5 |

Каждое тело получило ticks; 11-й reservation отклонён при ёмкости 10. Эти короткие
последовательные сценарии имеют разный прогрев JVM и не доказывают линейного роста
ёмкости. При лимите 1 и десяти телах nominal controller cadence — около 2 секунд,
в то время как native движение выполняет свой task manager. Боевые реакции на
массовой нагрузке, длинные сессии, CPU/GC profile и настройка actions/budget требуют
отдельного измерения. Синхронный native/SQL-вызов нельзя прервать бюджетом scheduler;
его превышение теперь видно, но latency guarantee не появляется.

## Найденное и исправленное

1. Scheduler не считал overrun последнего действия при достигнутом action limit.
   Исправлен учёт и добавлены last/max duration; проверено детерминированно.
2. Старый C3 **тестовый fixture** удалял source прямым DELETE players, оставляя его
   inventory. Последующая JVM могла переиспользовать этот ID: аудит SQL подтвердил
   старые вещи и сумму 987654 у нового тестового владельца. Это не копирование
   blueprint и не новый дефект production creation: штатное удаление очищает вещи.
   Fixture переведён на PlayerService.deletePlayerFromDB; добавлены assertions
   отсутствия orphan inventory и marker item. Финальный чистый прогон не содержит
   предупреждений о duplicate equipped items. Ранние диагностические PASS не
   используются как итоговая приёмка этого fixture.

## Артефакты и оставшиеся ограничения

Финальные локальные логи: workspace `work/c8-verified-20261107/*.log`;
сборка `work/c8-final-build.log`. Report содержит HEAD, hash исходников/runner,
пакета, diff и каждого лога. Docker создан с отдельным именем/меткой/tmpfs и остановлен
в finally; тестовые credentials вычищены. Временная база C5 тоже остановлена.
Рабочие БД/клиент/сервер пользователя не перезапускались и не патчились этим прогоном.

Не закрыты: два реальных клиента и отображение/пакеты/переподключение, расширение
карт/классов/навыков, live exchange, полноценный travel gateway и production soak.
W0, autonomous citizens/legions, LLM, offline rewards не реализовывались.
Функции остаются default-off. Следующий практический шаг — совместная проверка по
матрице, после неё дальнейший A1 отдельной задачей.
