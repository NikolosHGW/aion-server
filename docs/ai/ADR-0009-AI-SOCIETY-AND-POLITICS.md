# ADR-0009: autonomous organizations, allegiances and politics

**Status: Proposed (не принято технически).**

Кодовый аудит: [2026-11-06](LIVING_WORLD_INTEGRATION_REVIEW_2026-11-06.md).
В текущем коде есть `GROUP_INVITEOTHERFACTION`, `ALLIANCE_INVITEOTHERFACTION`
и `LEGION_INVITEOTHERFACTION`, default false. Они не образуют общую модель
дипломатии: hostility, rewards, online-проверки и siege ownership проверяются
другими путями. Этот ADR не включает флаги и не подтверждает mixed-faction support.

Организационный союз и native `PlayerAlliance` — разные понятия. Для создания,
приглашения, согласия, казны, PvP и осад нужны отдельные проверенные AI gateways;
они пока отсутствуют. Наличие `LegionService`/`SiegeService` не снимает эту предпосылку.

## Контекст

Изначальный `CITIZEN_AI` — один самостоятельный персонаж с занятиями. Новая продуктовая цель — дать гражданам и людям создавать политические и военные организации, союзы, конфликты, собственные планы и осады. В Beyond Aion native `race`, groups, legions, hostility и siege ownership могут быть связаны напрямую, а клиент 4.8 не обязательно понимает нестандартную дипломатию.

## Предлагаемое решение

1. Отделить `originRace` и `nativeFaction` от нового `politicalAffiliation` и `DiplomaticRelationship`. Политическое отношение **само по себе не** переопределяет native PvP hostility.
2. Создать домен `Organizations` только после работающего citizen lifecycle, members, group/inventory/economy. Membership в нативный легион — через проверенные Aion services; внутренние договоры, интересы и бюджеты — отдельное состояние с подтверждаемыми последствиями.
3. Договор имеет стороны, цель, условия, срок, статус, полномочия подписи, обязательства/ресурсы и историю; подписание и изменение require actor permission. Срыв объясним и участвует в памяти отношений.
4. У ИИ-командира есть ограниченные resources/soldiers/knowledge; командование — high-level goal/activity, не direct DB ownership transfer. Командиры не получают omniscience и не могут подменять игроков.
5. Этапы: сначала независимые легионы внутри native faction; затем договоры между native factions **без** отключения hostility; после полного server+client audit — mixed factions, parties, guilds, colors, trade, siege owner rules, all behind flags.
6. Осады Aion сохраняют native mechanics; AI участвуют по тем же проверенным боевым правилам. Вне расписания политики допустимы локальные предшествующие конфликты, не скрытые захваты fortress.

## Отвергнутые варианты

- Сразу разрешить всем атаковать всех/вступать в любую гильдию — ломает native assumptions и PvP safeguards.
- Объявить союз в БД достаточным для дружелюбного combat/party — опасное расхождение UI/сервера.
- Сделать все политические события скриптовыми сценами с гарантированным исходом — противоречит свободе действий.

## Приёмка

Первый AI-легион: можно проследить, почему создан, кто вступил, откуда средства, как принял решение. Две организации заключают реальный договор; репутационные последствия привязаны к событиям. На native 4.8 осаде запретить любые фальшивые ownership updates. Cross-faction поведение не включается без отдельной матрицы и решения.
