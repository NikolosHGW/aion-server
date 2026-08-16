# ADR-0005: persistence companion binding и goal intent

Статус: Accepted

Дата: 2026-08-09

## Контекст

Этап 2D хранит goal/execution session только в памяти и очищает её при
dismiss/logout/shutdown. Native owner `player_quests` уже надёжно хранит фактический
quest status и progress. Дублировать его в AI-таблице нельзя.

Решение напрямую реализует веху 1 и разделы 3.1, 4.3, 10.6 и 13
[`PRODUCT_VISION_AND_ROADMAP.md`](../../PRODUCT_VISION_AND_ROADMAP.md): постоянный спутник помнит
совместную цель, а native quest/reward не дублируются. Владелец одобрил
решение 2026-08-09 после проверки на отсутствие конфликта с product vision.

## Решение

1. Хранить два разных факта:
   - stable owner→companion binding;
   - выбранный goal intent: type, quest ID, format version и SHA-256 semantic fingerprint.
2. Не хранить AI-копию progress, execution state, native status, reward, position,
   target, combat signal или runtime session ID.
3. `goal choose` атомарно upsert-ит binding и intent до того, как команда
   сообщит успех. Ошибка DB отклоняет choose без частичного runtime attach.
4. Explicit `goal clear` удаляет intent. Dismiss/logout/shutdown его не удаляют.
   `COMPLETE` также не удаляется автоматически, чтобы status переживал restart.
5. После summon сервис читает intent, заново строит план из текущих game data,
   сравнивает version/fingerprint/allowlists/role/binding и только затем attach-ит tracker.
6. После attach весь progress/state вычисляется read-only из текущего owner
   `QuestState`. Null/START/REWARD/COMPLETE дают WAITING/ACTIVE-or-READY/COMPLETED по
   правилам ADR-0004.
7. Stale/corrupt/foreign binding никогда не исправляется автоматически: restore
   fail-closed, row сохраняется для диагностики, в status/log виден reason.
8. Feature disabled делает persisted intent dormant, но не удаляет его. Rollback кода
   и flags не требует менять native quest data.

## Схема

- `ai_companions`: `owner_player_id` PK/FK, `companion_player_id` UNIQUE/FK, `role`, timestamps.
- `ai_companion_goals`: `owner_player_id` PK/FK to binding, `goal_type`, `target_id`,
  `plan_version`, `semantic_fingerprint`, timestamps.

Отдельные таблицы не смешивают lifecycle компаньона с жизненным циклом одной
цели и позволяют будущему `companion create` переиспользовать binding.

## Отклонённые варианты

- Сохранять runtime snapshot/progress: второй journal и race с native persistence.
- Хранить только quest ID: не обнаруживает изменение quest/game data.
- Java serialization/full JSON plan: дублирует презентацию и static data, сложнее мигрируется.
- Использовать `player_settings`/`server_variables`: слабые constraints и чужая ownership.

## Приёмка

ADR и этап 2E приняты. Ручная проверка на обычном owner `Feel` подтвердила
restore выбранной цели до принятия native quest, при progress `1/3` и после
`COMPLETE`; native `complete_count=1`, повторный `today` вернул
`ALREADY_COMPLETE`, persistent rows не содержат shadow progress и не изменились
при logout/restart. Полный протокол находится в
[IMPLEMENTATION_PLAN_2E](IMPLEMENTATION_PLAN_2E.md).
