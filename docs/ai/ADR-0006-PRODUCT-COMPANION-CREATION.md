# ADR-0006: product creation of a persistent companion Player

Статус: Accepted

Дата: 2026-09-11

Baseline: `3dd9e5d15` (`feat(ai): persist companion quest goals`).

## Контекст

2A–2E материализуют заранее созданного `Cool` из закрытого account `Test`.
Это доказало server-controlled `Player`, бой, goal и persistence, но не продуктовое
создание личного спутника. Штатный `CM_CREATE_CHARACTER` использовать нельзя:
он привязан к `AionConnection`, character-selection response и лимиту слотов
аккаунта живого игрока.

При этом доменный путь `PlayerService.newPlayer()` → `storeNewPlayer()` отделён
от packet handler и создаёт обычные common data, appearance, стартовые skills и
items. `players.account_id` не имеет FK в login DB; для 2F используется уже
существующий деактивированный service-account `Test` (id `13`). Он является
техническим контейнером AI-body и не отображается в character selection владельца.

## Решение

1. `//companion create` — отдельное явное и идемпотентное продуктовое действие.
   Оно не summon-ит body и не меняет owner journal/inventory/progression.
2. Для owner детерминированно резервируется короткое буквенное DB-имя. Таблица
   `ai_companion_creation_claims` хранит owner, имя, exact host account и после
   создания exact companion player ID.
3. После reservation создаётся новый level-1 starting-class `Player` через
   штатный доменный character-creation pipeline. Он получает только обычное
   стартовое состояние, заданное серверными player initial data; template `Cool`
   не читается и не клонируется.
4. Claim и owner↔companion binding финализируются одной DB-транзакцией после
   проверки players/appearance/skills/inventory. UNIQUE owner/body/name и FK
   запрещают второго companion и присвоение чужого body.
5. Если процесс остановился после reservation или сохранения Player, повторный
   `create` продолжает exact claim и не создаёт новое имя/body. Collision без
   claim отклоняется fail-closed.
6. При включённом 2F `summon` разрешает body только через exact claim+binding;
   `template_character_id` остаётся лишь rollback-путём при выключенном 2F.
7. `dismiss`, logout и shutdown удаляют только runtime. Claim, body, binding и
   goal не удаляются. Удаление companion не входит в 2F.
8. Feature flag default-off. 2F требует включённую 2E persistence и заранее
   применённый focused DDL.

## Почему не аккаунт владельца

Персонаж на account владельца занял бы один из обычных восьми character slots,
появился бы в character selection и смешал client-owned и server-controlled
lifecycle. Это противоречит продуктовому смыслу персонального спутника.

## Почему не новый login-account на каждого companion

GameServer не владеет регистрацией/паролями LoginServer. Создание auth account
из игрового сервера расширило бы trust boundary и 2F без продуктовой пользы.
Один закрытый AI-host account минимален; будущая persistent progression должна
отдельно проверить shared account warehouse и другие account-scoped системы.

## Не входит

Имя/appearance editor, level sync, развитие, сохранение runtime position,
новые skills, group, самостоятельные AI-жители и economy.

## Фактическая приёмка

На реальном клиенте обычный owner успешно прошёл first/repeat `create`,
`dismiss`/`summon`, follow, logout/relogin и полный restart GameServer. Во всех
случаях вернулся один и тот же companion `player_id=106124`; другой персонаж не
смог его присвоить. SQL подтвердил ровно один claim, binding и body
(`body_duplicates=1`). Body находится на закрытом host account `Test` (`id=13`),
а character slots account `Nikolos` не менялись.

## Наблюдения и ограничения

- После применения DDL оба persistence/creation флага всё равно остаются
  default-off: включение — явный opt-in конкретного runtime, а выключение
  возвращает безопасный rollback-путь без удаления claim/body/binding.
- Shared host account безопасен для текущего level-1 starter state; перед
  inventory/economy progression нужно отдельно проверить account-scoped warehouse
  и другие account-scoped системы.
- Legacy 2E binding без creation claim при включённом 2F намеренно не
  усыновляется: это fail-closed migration boundary, а не путь к второму body.
- Редактор имени/внешности, progression, новые skills, group и удаление/re-roll
  остаются за границей 2F.
