# Living World Architecture — расширение Beyond Aion 4.8

**Статус:** Proposed / дизайн, код не реализован.
**База:** `ADR-0001…0006`, `ARCHITECTURE.md`, `DELIVERY_PLAN.md`.
Новые действия требуют проверенных gateways и native world rules; наличие
native сервиса не означает наличия пригодного для AI gateway. Конкретные пробелы
описаны в [аудите интеграции](LIVING_WORLD_INTEGRATION_REVIEW_2026-11-06.md).
Этот документ не утверждает реализацию новых API, таблиц или флагов.

## 1. Задача

Получить связный цикл: **мир меняется → персонажи узнают об этом → ставят цели → действуют через Aion → фиксируются последствия → меняются отношения и мир**. Люди и явно обозначенные ИИ-персонажи должны пользоваться одними событиями мира, но иметь разные источники намерений и разную модель наград.

Это не симулятор «тысяч онлайновых игроков». Ценность определяется содержательными выборами и причинно-следственными последствиями, а не количеством AI-врапперов в World.

## 2. Логическая схема

```text
                  Human players / PERSONAL_COMPANION / CITIZEN_AI
                                          |
   observations (native events, witnessed actions, shared knowledge)
                                          v
    [Verified Event Ingestion] ---> [append-only World Event Ledger]
                                          |
               +--------------------------+------------------------+
               |                          |                        |
               v                          v                        v
     [World/Region States]       [Memory & Beliefs]       [Organizations]
    угрозы, маршруты, влияние    личные знания, связи     легионы, договоры
               |                          |                        |
               +--------------------------+------------------------+
                                          |
                              [Goal / social decisions]
                                          |
                               [Intent / Activity plan]
                                          |
                           [Policy and Action Gateway]
                                          |
                            Native Beyond Aion services
                  (movement, combat, skills, items, economy, siege)
```

`World Event Ledger` — журнал новых **мировых фактов**, а не второй ledger нативных предметов, денег, квестов и опыта. Native data — единственный источник истины этих доменов. Проекция использует отдельную монотонную позицию committed-событий своего aggregate и bounded replay; случайный UUID не является порядком или курсором времени.

## 3. Типы данных и владельцы истины

| Домен | Authoritative source | Новая проекция |
|---|---|---|
| HP, XP, уровни, предметы, кинаха, навыки, native quests | Beyond Aion native services + DB | Ссылки на подтверждённые исходы, никогда duplicate native journal |
| Движение, урон, бой, PvP hostility | Existing Aion world / gateways | Fact event **после** native success, необязательная статистика |
| Memory персонажей | New World Memory store | Знания с источником, временем, достоверностью, forgetting policy |
| Социальные отношения | New Relationship store | Доверие/обязательства/обиды на основании событий, не просто grind rep |
| Региональная угроза и жизненный цикл поселений | New Region State module | Calmer/threat/attack/occupation/rebuild, versioned |
| Легионы и дипломатия | Native legion membership, где поддерживается; новый organization/diplomacy state поверх него | Собственные договоры/интересы; не обходить native faction checks |
| Долгосрочные цели AI | New Goals and Commitments store | Desired outcome, priority, plan, status, provenance |
| Личная биография игрока | World Event Ledger + privacy scoped projections | Выборка значимых, проверенных фактов для журнала и компаньона |

## 4. Контракт подтверждённого события

Пример предложения формата — не существующий JSON/Java API:

```json
{
  "event_id": "uuid",
  "kind": "CARAVAN_REACHED_SETTLEMENT",
  "schema_version": 1,
  "occurred_at": "2026-11-06T18:30:00Z",
  "region_id": "example-region/north-road",
  "actor_ids": [105, 212],
  "entity_ids": ["caravan:42"],
  "causation_id": "caravan-run:42",
  "native_outcome_ref": "verified:caravan-arrival:42",
  "result": "SURVIVED"
}
```

`region_id` в примере условный и должен заменяться на проверенный content ID. Реальный event envelope обязан содержать стабильный identity, actor identity type (human / companion / citizen / system), schema version, evidence reference, causal chain, world/instance context, transaction boundary, timestamp. Не включать в одну публичную запись секреты, чужие приватные состояния или всеведущие сведения.

**Правила:**

1. Регистрировать игровой исход только после успешного authoritative commit. До этого возможен event intent/request, но он не является доказанным фактом.
2. При возможности использовать DB outbox в той же транзакции, что и новая world-state mutation; для существующих native операций, где атомарная запись недоступна, применять reconciler/ID проверки и не сообщать успех раньше подтверждения.
3. У события есть UUID и отдельный стабильный ключ native-источника/occurrence с UNIQUE constraint. Повтор той же причины с новым UUID не создаёт новый факт; тот же ключ с другим payload отклоняется fail-closed. Потребители идемпотентны; replay не выдаёт награды повторно.
4. Упорядочение событий гарантировать **локально для aggregate/region**, а не глобально по всем игрокам; version checks при конкурентной записи.
5. Логировать failure/lag/invalid event, откат/реплей и иметь feature flag для каждого домена.

В текущем коде `QuestService.finishQuest` и `QuestEngine.onQuestCompleted`
обозначают in-memory completion, не общий durable commit наград и квеста.
`PlayerQuestListDAO.store` выполняет отдельные commits и логирует ошибки.
В [W0](IMPLEMENTATION_PLAN_W0.md) предлагается сохранять ограниченное наблюдение
как неподтверждённый candidate и подтверждать только по точному сохранённому
native evidence. C2 ActionSession действует в пределах runtime-сессии;
BodyCheckpoint сохраняет тело при teardown. Ни один из них не является durable
event outbox или историей присутствия компаньона.

## 5. Три уровня «памяти»

**World Chronicle:** подтверждённые значимые факты для сервера. Запись «крепость перешла к легиону» — только при реальной успешной передаче по проверенным правилам.

**Personal Memory:** субъект помнит: с кем путешествовал, кто помогал, что ему было обещано, чего он хотел добиться, что сам видел. Не хранит чужие quests/inventory и не получает данные через глобальное сканирование World.

**Beliefs / rumors:** знания с `source`, `learned_at`, `confidence`, `last_verified_at`, `visibility`. NPC может ошибаться или повторять слух; достоверный журнал и слухи нельзя смешивать. Память стареет, пересматривается при новых свидетельствах; скрытая информация не переходит к каждому AI автоматически.

`Memory` — не просто увеличение reputation score. У памяти должен быть *смысл*: контекст помощи, совместная цель, долг/обещание, близость к персонажу и последствия решения. Данные для текстовых реплик извлекаются из ограниченного списка фактов. LLM, если включён, получает только разрешённый snapshot, не может выдумывать изменения мира.

## 6. Companion и citizen пользуются общим миром по-разному

`PERSONAL_COMPANION`: цели владельца > его разрешения > советы партнёра; будущая
память учитывает user intent и предпочтения из добровольных решений.
Синхронизация уровня 1:1 запланирована в C3 и ещё не реализована. Принятые owner
contribution attribution и native owner quest journal сохраняются без второй награды.

`CITIZEN_AI`: независимые goals, schedules, beliefs, relationships, real economic budget. XP/rewards — собственные и только нативные. Он может менять цели после новых сведений и принимать обещания, которые действительно может исполнить. Не даём прямых database/admin powers.

## 7. Автономная жизнь без лишнего off-screen farming

Важно разделить:

- **Native actor activity:** при любом числе людей, включая ноль, combat/movement/collision/loot выполняются физически присутствующим actor через native Aion. Наличие наблюдателя не меняет этот контракт. Нет телепорта, phantom kills или дорисованного лута.
- **Strategic world-state progression (off-screen):** редкие, бюджетированные изменения агрегатов региона: нарастание угрозы, истечение договоров, движение фронта, готовность каравана, восстановление поселения. Они могут запускать будущие реальные игровые события при materialization. В первой версии — без начисления AI XP/items/kinah и без автоматического захвата native крепостей.
- **Offline individual progression:** запрещена. Выходящий AI не получает личные предметы, опыт и кинаху за неразыгранные часы. ADR-0008 не создаёт исключения для offline farm.

Strategic region-state progression — отдельная будущая система, сейчас только
Proposed. До реализации нужны таблица разрешённых переходов, bounded work и catch-up,
version/CAS, контроль времени, детерминированные тесты, replay, isolation и fail-closed.
Давление угрозы или готовность не является запасом native предметов/денег/войск.
Смена режима при появлении игрока не создаёт второй encounter и не переигрывает
уже подтверждённый native результат. W0 не запускает ни переходы, ни spawns.

`World Director` не делает «противник захватил крепость» простым SQL update. Он может повышать цель агрессивного легиона, инициировать допустимую подготовку, но финальный захват проходит siege gateway с подтверждённым исходом. Для малых новых застав может существовать отдельная off-screen native-independent state machine только после явного ADR о честном расчёте, включающем реальные гарнизоны и ресурсы.

При появлении наблюдателя агрегатное состояние материализуется через проверенные spawns и bounded transition. Нельзя материализовать бой там, где уже действует другой инстанс, или менять результат действия клиента задним числом.

## 8. Отдельная «политическая» identity

```text
Character:
  origin_race            -> native Elyos/Asmodian, не менять автоматически
  native_faction         -> Aion native hostility/capabilities
  political_affiliations -> легион/фракция/договоры (new domain)
  relationships          -> личные отношения/обязательства
```

Первый этап политики — отношения и конфликты внутри одной native race без смены правил PvP. Далее cross-faction diplomacy поверх ограниченной модели, затем при успешном audit — смешанные отряды/легионы и новые правила осад. Перевод политического союзничества в реальное friendliness/PvP, party, legions, quest eligibility, flight zones, duel, trade, packet colors и native fortress ownership потребует отдельной матрицы code/client проверки. Без неё политики — не gateway allowlist.

## 9. Планирование и свобода действий

`Opportunity` содержит `objective`, `known facts`, `capability preconditions`, `possible effects`, `cost/risks`, `expected time`, `available actors`; **не** содержит единственный «правильный сюжет».

Пример освобождения деревни задаёт одну цель с набором будущих действий:
`SCOUT`, `SUPPLY`, `RECRUIT`, `INTERCEPT`, `ASSAULT`. Каждое становится доступным
только после реализации своего gateway и контента. Порядок действий может менять
результат: коммутативность не предполагается, конфликтующие переходы требуют
version checks и тестов. Игрок составляет план в пределах исполнимых возможностей.

## 10. Надёжность, контроль, fairness

- Предлагаемые, пока не существующие настройки: `ai.world_events.enabled=false`, `ai.world_memory.enabled=false`, `ai.region_sim.enabled=false`, `ai.society.enabled=false`, `ai.diplomacy.enabled=false`. Активация только после реализации config/DDL/tests; для W0 — только первые две.
- Kill switch по индивидуальному AI, легиону, региону и домену. Выключение оставляет native Aion работоспособным.
- Привязать стратегические обновления к ограниченному scheduler budget и QoS: снижение частоты стратегических расчётов раньше ухудшения game tick.
- Обязательные метрики: event ingestion lag, replay errors, region transitions, actor plan/replan reasons, off-screen changes, memory retention, agent CPU, native consistency mismatches, world-state materialization conflicts, count of unsolicited AI invitations.
- Fairness: небольшое число непредсказуемых AI не может бесконечно контролировать рынок или territory. Расписания осад, safe entry, no loss on absence, anti-grief, защита новых игроков, запрет принудительного PvP за пределами согласованных правил.
- Anti-FOMO: состояние мира меняется, но важные отношения/биографии не исчезают из-за отсутствия; новый вход даёт занятие за 20–40 минут. Мир не должен требовать ежедневного логина.
- Все персональные данные и социальные события хранить в минимально необходимом объёме; учитывать privacy/visibility при показе хроник.

## 11. Открытые ревью-вопросы перед кодом

1. Где конкретно завершается транзакция native action, и можно ли безопасно публиковать подтверждённые события без race с rollback?
2. Какие классы/сервисы 4.8 проверяют native faction hostility, native legion ownership и fortress capture?
3. Как native gameplay реагирует на `Player.isOnline()==false` при торговле, party, guild и PvP?
4. Где проходит граница изоляции shared AI host account при warehouse, mailbox, broker и economy?
5. Какие existing data/content/spawn entry points поддержат минимальный dynamic region без ломания клиентского протокола?
6. Как будет работать kill switch во время pending native action и world-state materialization?

Считать документ архитектурным направлением; методы/таблицы выше — контракты **для последующего кодового аудита**, не подтверждённые API.
