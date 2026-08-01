# ADR-0002: read-only eligibility и планирование квестовой цели

Статус: Accepted

Дата: 2026-08-01

Дата принятия владельцем проекта: 2026-08-01

Связанные документы: [ADR-0001](ADR-0001-SERVER-CONTROLLED-PLAYER.md),
[принятый этап 2A](IMPLEMENTATION_PLAN_2A.md),
[план этапа 2B](IMPLEMENTATION_PLAN_2B.md)

## Контекст

Этап 2B должен предложить connected-владельцу companion одну реальную
квестовую цель и сохранить выбор только в runtime. Он не должен принимать
квест, менять журнал, инвентарь или опыт, отправлять quest/dialog packets либо
писать в БД.

В сервере нет единого чистого API «этот Player может прямо сейчас принять и
выполнить структурированный квест». Ближайший метод —
[`QuestService.checkStartConditions()`](../../game-server/src/com/aionemu/gameserver/services/QuestService.java#L278).
Он проверяет состояние журнала, race/level/class/gender/rank, XML prerequisites,
inventory, craft skill и faction limits. При `warn=false` он не отправляет
пакеты, однако
[`XMLStartCondition.checkEquippedItems()`](../../game-server/src/com/aionemu/gameserver/model/templates/quest/XMLStartCondition.java#L107)
в этом режиме вообще пропускает equipment condition. Кроме того, capacity
обычного quest log проверяется только внутри мутирующего
[`QuestService.startQuest()`](../../game-server/src/com/aionemu/gameserver/services/QuestService.java#L400).

Структура сценария разделена между `quest_data.xml`, XML script metadata и
произвольными Java handlers. Вызов
[`QuestEngine.onDialog()`](../../game-server/src/com/aionemu/gameserver/questEngine/QuestEngine.java#L152)
не является запросом метаданных: handler может запустить квест, изменить
`QuestState`, выдать/забрать item и отправить dialog packets.

## Принятое решение

1. Начать с configured allowlist максимум из трёх вручную проверенных quest
   IDs; первоначальная эксплуатационная конфигурация содержит один ID.
2. Поддержать только стандартный XML `monster_hunt`, представленный
   [`MonsterHuntData`](../../game-server/src/com/aionemu/gameserver/questEngine/handlers/models/MonsterHuntData.java#L27)
   и `QuestTemplate.quest_kill`. Java quest scripts, generic `xml_quest`,
   collect/talk chains, repeatable/daily, faction, event, test и instance quests
   исключить fail-closed.
3. Добавить отдельный read-only entry point штатной start-condition логики. Он
   должен сохранить проверки equipment и quest-log capacity, но не иметь
   packet/DAO/dialog/item/XP side effects. Существующее поведение перегрузок
   `checkStartConditions(..., warn)` не менять.
4. `ReadOnlyQuestEligibility` объединяет результат этого entry point с
   fail-closed проверкой поддерживаемой формы template/script и доступности
   статических spawn metadata на текущей карте владельца.
5. `QuestGoalPlanner` работает только с immutable DTO. При одинаковом snapshot
   игрока, allowlist и времени в одном eligibility window сортировка полностью
   детерминирована; `Rnd` не используется.
6. `CompanionGoalSession` хранит последнее предложение и выбранный план только
   в памяти и принадлежит lifecycle текущего companion. `choose` означает
   runtime selection, а не `QuestService.startQuest()`. Повторная валидация
   сравнивает semantic fingerprint из quest ID, minimum level, prerequisites и
   ordered objective type/target IDs/count, исключая динамические
   distance/reference coordinates и presentation text.
7. Локализованные имена передаются connected-владельцу как client-side L10n
   markers/links (`QuestTemplate.getL10n()`, `ChatUtil.quest/path/itemName`).
   Серверная копия русских строк и описаний не создаётся.
8. Ожидаемые исходы выбора представлены structured result: `CHOSEN`,
   `NO_OFFER`, `STALE_OFFER`, `INELIGIBLE`, `SESSION_MISMATCH`.
   Настоящий stale очищает runtime offer/chosen без exception, quest mutation
   или persistence.

Запрещённый call boundary этапа 2B:

```text
QuestEngine.onDialog / AbstractQuestHandler.onDialogEvent
QuestService.startQuest / finishQuest / addOrUpdateQuest
QuestState setters / QuestStateList.addQuest|deleteQuest
ItemService / XP rewards / PlayerQuestListDAO.store
client packet handlers / synthetic AionConnection
```

## Почему allowlist, а не поиск по всем templates

Автоматический поиск охватывает больше контента, но `QuestTemplate` не хранит
полную последовательность действий и даже не сохраняет `quest_zone` в runtime
модели. Start/end NPC находятся в XML metadata либо Java handler, а
произвольный handler может накладывать условия, которые невозможно безопасно
вычислить без исполнения кода. Совпадение level/race/prerequisites поэтому
может дать ложное обещание доступности.

Allowlist не заменяет eligibility. Он ограничивает множество квестов теми, для
которых на code review доказаны handler type, структура objectives, NPC spawns
и отсутствие нестандартного dialog path; после этого каждый кандидат всё равно
проходит полную read-only проверку текущего владельца. Это минимизирует и false
positive, и риск побочного эффекта.

## Рассмотренные варианты

### A. Автоматический поиск по всем `QuestTemplate`

Плюсы: не требует операторского списка, автоматически покрывает новый контент.

Минусы: template не раскрывает произвольную Java-логику, start/end NPC и
маршрут; `quest_zone` отсутствует в `QuestTemplate`; надёжной общей проверки
доступности карты нет.

Риски: ложная eligibility, неверные objectives/координаты, случайное включение
event/test quest. Решение: отклонить для первого 2B.

### B. Allowlist 1–3 проверенных IDs + read-only штатная eligibility

Плюсы: малая поверхность данных, воспроизводимые планы, каждый ID можно
закрепить fixture-тестом, безопасный fail-closed результат.

Минусы: требует ручной верификации и отдельного набора IDs для разных
race/level/map; не масштабируется без каталога capabilities.

Риски: allowlist может устареть после изменения game data. Снижается startup
validation и тестами. Решение: рекомендуется; начать с одного ID.

### C. Исполнить dialog handler в sandbox/dry-run

Плюсы: теоретически учитывает custom Java conditions.

Минусы: handlers не спроектированы как транзакционные или чистые; side effects
распределены по state, inventory, packets, tasks и другим сервисам. Надёжный
rollback потребовал бы копии значительной части `Player`.

Риски: скрытая мутация реального владельца. Решение: отклонено.

## Последствия

Положительные:

- planner не имитирует клиентский пакет и не требует connection у companion;
- runtime selection не влияет на реальный quest log владельца или AI;
- первый slice имеет доказуемый формат `NPC -> kill targets -> NPC`;
- расширение на новый quest type требует явного extractor/capability review.

Отрицательные:

- минимальный 2B не обещает лучший квест среди всего контента;
- description текста на сервере нет: клиент локализует ссылки/markers, а
  formatter обязан явно показывать отсутствующие поля как unknown;
- «static spawn на текущей карте» не доказывает навигационную достижимость;
- понадобится небольшая локальная доработка `QuestService` и
  `XMLStartCondition`, покрытая regression-тестами.

## Условия принятия и пересмотра

ADR принят со следующими обязательными ограничениями:

- allowlist по умолчанию пуст и feature flag выключен;
- первая версия исключает repeatable/daily и все типы кроме проверенного
  `monster_hunt`;
- новый eligibility entry point доказан тестами как packet-free и mutation-free;
- `goal choose` не вызывает quest handlers и повторно проверяет eligibility;
- удаление companion, logout и runtime-disable очищают goal session.

Решение пересматривается перед автоматическим discovery, collect/talk goals,
принятием квестов или исполнением цели.

## Соответствие реализации

Реализация этапа 2B от 2026-08-01 следует принятому варианту B: default-off
flags, configured allowlist, exact `MonsterHuntData`/`MonsterHunt`, отдельный
полный тихий read-only eligibility API и runtime-only session. Первый fixture
зафиксирован как `1102` (Elyos/Poeta). Команда выбора называется
`//companion goal choose` и явно сообщает `runtime AI goal only; quest not
accepted`. Automatic discovery, quest handlers, persistence и отдельный goal
scheduler отсутствуют.

При первой ручной проверке полное record-equality планов дало ложный
`STALE_OFFER`: planner менял `selectionReason`, а distance/reference также
могли измениться при движении владельца. Реализация исправлена явным semantic
fingerprint и structured choose outcomes. Автоматический reactor и повторная
ручная приёмка двумя клиентами успешно пройдены 2026-08-01: выбор после
движения создаёт только runtime chosen plan, quest journal/DB не меняются.
Это подтверждение не расширяет решение на исполнение цели, navigation, combat,
groups, loot, rewards, economy, persistence или этап 2C.
