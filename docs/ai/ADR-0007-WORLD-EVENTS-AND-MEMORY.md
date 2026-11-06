# ADR-0007: verified world events, memory and relationships

**Status: Proposed (не принято технически).**

Кодовый аудит: [2026-11-06](LIVING_WORLD_INTEGRATION_REVIEW_2026-11-06.md).
Первый предлагаемый срез: [W0](IMPLEMENTATION_PLAN_W0.md). ADR-0001…0006
остаются Accepted; этот ADR их не заменяет и не переписывает историческую приёмку.

## Контекст

ADR-0002/0004/0005 сохраняют native quest journal и goal intent. Новый продукт требует помнить доказанные совместные поступки, социальные связи и изменения мира **без копии нативного состояния**. Пока C1/C2 проверяют тела компаньонов; общая мировая событийность ещё не реализована.

## Предлагаемое решение

1. `WorldEvent` фиксирует проверенный факт *после* успешного native/world action, имеет immutable ID, version, causation, timestamp, actor/target, location, result и evidence. События из нетранзакционной native API не обещать как durable до reconciliation.
2. `WorldChronicle`, `ActorMemory`, `RelationshipState` — раздельные read-model/projections. native quest / XP / items не дублируются. ActorMemory фильтруется по observer/received-information, личной visibility и TTL.
3. `Memory` содержит эпизоды, цели, знакомства, promises, confidence; знакомство — не global access к персональному состоянию.
4. Projections идемпотентны по event ID и aggregate version, поддерживают bounded replay; временные ошибки не порождают повторные награды.
5. Спутнику выдаётся только разрешённый owner-and-companion snapshot. LLM необязателен, не имеет полномочий менять состояние. Реальные действия всегда идут в native gateways.
6. Persistence таблиц/DAO и transaction binding решить отдельным кодовым аудитом по `C2 BodyCheckpoint` и native services; нельзя записывать persistent event через случайный read-only observer до определения атомарности.
7. Runtime action receipt и candidate-наблюдение не являются confirmed event.
   Stable source-occurrence key дедуплицируется независимо от нового UUID;
   конфликт payload отклоняется. Read-only status ничего не публикует и не
   изменяет; отдельный reconciler может писать только новый memory-домен.
8. Для W0 native evidence доказывает лишь сохранённый COMPLETE квеста 1102.
   Оно не доказывает атомарное сохранение всех наград или участие компаньона
   во всём прохождении. Видимость совместного эпизода требует отдельно
   подтверждённого контекста пары; без него прошлое не достраивается.

## Альтернативы

- «Один счётчик репутации» — не хранит конкретную историю и причинность.
- «LLM запоминает всё текстом» — недостоверно, невоспроизводимо, нет authority.
- «Дублировать QuestState, inventory и combat result» — конфликт источников истины.

## Последствия

Добавляется небольшой event/persistence overhead. Нужны ordering, reconciliation, privacy и bounded retention. Взамен появится проверяемый фундамент для компаньона, граждан и легионов.

## Минимальная приёмка

Один подтверждённый совместный эпизод владельца и компаньона, корректная память после restart, два разных владельца не читают чужую приватную историю, дубликат/replay не меняет награды и не создаёт двух записей. `ai.world_memory.enabled=false` оставляет старую функциональность.
