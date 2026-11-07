# AI MVP: текущее состояние

Дата: 2026-11-07

## Текущий порядок разработки

Владелец подтвердил: сначала полноценный компаньон, затем самостоятельные
AI-игроки. Аудит базы `60c7a17fe` выявил ограничения прототипа: одна активная
сессия компаньона на весь сервер, GM-доступ к командам, фиксированное стартовое
тело, отсутствие общего исполнения навыков и полного сохранения изменяемой прогрессии.

Текущий рабочий план: [DELIVERY_PLAN.md](DELIVERY_PLAN.md).
Серверная реализация C1–C7 и автоматическая часть [C8](IMPLEMENTATION_PLAN_C8.md) завершены.
Общая клиентская приёмка по [матрице](COMPANION_ACCEPTANCE_MATRIX.md) начата частично
и 2026-11-07 приостановлена владельцем. Полная продуктовая приёмка C8 открыта.
После отдельного разрешения реализован узкий W0: native сдача 1102 → личная память →
read-only status после restart. [Аудит и границы](W0_IMPLEMENTATION_2026-11-07.md),
[серверные проверки](W0_VALIDATION_2026-11-07.md). Игровой W0 gate тоже открыт.
Следующий кодовый срез — маленький P1 без LLM; затем автоматизация рутины входа и
общий игровой проход C1–C8/W0/P1 перед A1/A2. A1–A7 пока не реализованы.
Создание по внешности/классу своего персонажа и неизменяемые AI-префиксы закреплены
в [ADR-0010](ADR-0010-COMPANION-CHARACTER-SNAPSHOT.md). C4 покрывает первые роли
гладиатора и клерика в прежней ограниченной области разрешённых боевых целей.

Документальный пакет Living World V2 сверён с фактическим HEAD
`56ef9b6cdae95ada801c5885ea8f62fecd507fd2` на 2026-11-06 и интегрирован с
поправками по коду. Это утверждённое продуктовое направление, не реализованный
Living World. ADR-0007…0009 остаются Proposed. Результаты и ограничения:
[интеграционный отчёт](LIVING_WORLD_INTEGRATION_REVIEW_2026-11-06.md).
[Исходный проект W0](IMPLEMENTATION_PLAN_W0.md) не был разрешением на кодирование
в документальном проходе. Реализация отдельно разрешена 2026-11-07;
C3–C8/A1–A7 сохраняются, общий Living World пока не реализован.

Код C1 реализован; полная сборка — 271 тест без ошибок. На свежей MariaDB 11.4
проверены параллельное создание двух постоянных тел, повторное создание без
дублей и загрузка прежних связей после перезапуска. Исправлен найденный при этой
проверке deadlock финализации создания. Двухклиентская приёмка C1 ещё не проведена.
Подробности: [C1_VALIDATION_2026-11-04.md](C1_VALIDATION_2026-11-04.md).
В C2 добавлены локальные action receipts и отслеживание реального движения,
отдельное отображаемое имя, сохранение личного состояния тела одной транзакцией,
защита общего аккаунтного состояния и retry замороженного снимка при SQL-ошибке.
Проверки: [C2_VALIDATION_2026-11-06.md](C2_VALIDATION_2026-11-06.md).
В C3 добавлены immutable appearance/class blueprint, создание по персонажу своего
аккаунта, постоянный ник с `[AI:C]`, синхронизация уровня и обычных native навыков
без повторных наград. Reactor: 293 теста; свежая БД и отдельные JVM create/restore.
Подробности и границы: [C3_VALIDATION_2026-11-06.md](C3_VALIDATION_2026-11-06.md).
В C4 добавлены native skill actions, Healing Light владельцу, Smite и два звена
серии гладиатора, короткое checked сближение, action receipts и отмена delayed
effects под lock текущей сессии. Нативная пометка ожидающего смертельного удара
освобождается только своим действием. Проверки и ограничения:
[C4_VALIDATION_2026-11-06.md](C4_VALIDATION_2026-11-06.md).
В C5 добавлены native экипировка и мгновенные HP/MP зелья, просмотр реального
инвентаря и атомарная передача целого предмета/стека при выходе владельца из мира.
Передача сохраняет ID, квитанцию и защищает от поздних записей старого экземпляра.
Без выхода live обмен пока не реализован. [Проверки C5](C5_VALIDATION_2026-11-06.md).
В C6 добавлены bounded recovery, watchdog реального движения и локальные назначения
`move here|target`. [Проверки C6](C6_VALIDATION_2026-11-06.md): 315 тестов и native ground segment.
C7 добавляет каталог escort/support/rest/quest, подсказку `next` и второй
проверенный native quest 1120. [Проверки C7](C7_VALIDATION_2026-11-06.md):
320 тестов, native hook и separate-JVM restore 4/9.
C8 добавляет воспроизводимый fresh-DB runner, 9 отдельных JVM-фаз, 0/2/10 native
тел, production activity/dismiss/checkpoint retry и точный учёт превышения бюджета
последним действием scheduler. [Результаты](C8_VALIDATION_2026-11-07.md).
Владелец разрешил последовательно реализовать и коммитить C5–C8 без нового согласования.
Владелец 2026-11-06 отложил клиентскую приёмку всех этапов до общего игрового
прохода. C1/C2/C3/C4/C5/C6/C7 не объявляются проверенными в игре. На каждый законченный этап —
отдельный коммит; историческая ветка неизменна.
Исторические 2A–2G остаются принятой основой и не означают готовность всего продукта.

## Рабочая ветка после обновления 4.8

Дальнейшая разработка ведётся в `integration/ai-4.8`, созданной от
`feature/ai-mvp` и объединённой с `4.8` на `822ec1fc2`.
`feature/ai-mvp` сохраняется как историческая ветка на `1f6dfd9c2`:
её не изменять, не перебазировать и не удалять.

Сборка непосредственно после слияния прошла: 254 теста, failures/errors/skips `0/0/0`.
На чистой базе проверены запуск сервера, создание и повторная загрузка тела
компаньона, восстановление цели после перезапуска, совместимость квеста 1102
с обновлёнными данными и расчёт скорости движения. Эти проверки выполнялись
через серверные сервисы; повторная ручная проверка follow/боя в клиенте
после слияния ещё не проведена.

По решению владельца используем свежую БД из SQL репозитория. Сохранение
старой экспериментальной БД и отдельная миграция её данных не входят в работу.
Уточнение от 2026-11-06: до первого production-релиза AI можно переписывать
существующие определения наших AI-таблиц без цепочки миграций промежуточных версий.
Все копии определений SQL обновляем согласованно; историю хранит Git.
Это не отменяет проверку создания чистой БД и сохранения состояния после restart.
Полное правило — в разделе «Как ведём работу» [DELIVERY_PLAN.md](DELIVERY_PLAN.md).
Подробности интеграции и дальнейших обновлений:
[`UPSTREAM_INTEGRATION_2026-11-04.md`](UPSTREAM_INTEGRATION_2026-11-04.md).

## История принятого AI MVP

Это короткая точка входа в эксперимент. Главный продуктовый ориентир —
[`PRODUCT_VISION_AND_ROADMAP.md`](../../PRODUCT_VISION_AND_ROADMAP.md). Технические границы и
порядок этапов задаёт [`AION_AI_MVP_SPEC.md`](../../AION_AI_MVP_SPEC.md); детальные планы и ADR нужно
читать только когда задача затрагивает соответствующую границу.

## Принятая база

| Slice | Результат | ADR | Commit |
|---|---|---|---|
| 0/1 | `Player` без `AionConnection`, видимость, movement, cleanup | [ADR-0001](ADR-0001-SERVER-CONTROLLED-PLAYER.md) | `cf3656ba8` |
| 2A | один PERSONAL_COMPANION, summon/dismiss, follow/stay | ADR-0001 | `5747dbff6` + `cf3656ba8` |
| 2B | read-only `today`, offer/choose/status/clear для exact quest 1102 | [ADR-0002](ADR-0002-READ-ONLY-QUEST-GOAL-PLANNING.md) | `6f354a9a9` |
| 2C | owner-triggered checked basic hit, physical companion, reward attribution owner | [ADR-0003](ADR-0003-SERVER-CONTROLLED-COMBAT-ACTIONS.md) | `ba6320ce1` |
| 2D | native 1102 tracking, quest-scoped combat, 3/3/turn-in/COMPLETE | [ADR-0004](ADR-0004-COMPANION-QUEST-EXECUTION-TRACKING.md) | `c3fad073c` |
| 2E | persistent owner→companion binding и chosen goal intent; restore WAITING/1/3/COMPLETE | [ADR-0005](ADR-0005-COMPANION-GOAL-PERSISTENCE.md) | `3dd9e5d15` |
| 2F | explicit create и immutable ownership постоянного PERSONAL_COMPANION вне slots owner | [ADR-0006](ADR-0006-PRODUCT-COMPANION-CREATION.md) | `03eb3b7d1` |

Все шесть ADR и этапы 1/2A/2B/2C/2D/2E/2F приняты владельцем после ручных
проверок в реальном клиенте. Финальная автопроверка 2F: 166 tests,
failures/errors/skips `0/0/0`.

2G Navigation Hardening принят вручную в реальном Aion client. Это не новый
продуктовый slice и не новый ADR: он узко укрепляет принятый 2A follow. До
full-reactor проверки finalization targeted набор содержал 73 tests,
failures/errors/skips `0/0/0`.

## Неизменные инварианты

- AI body — fully loaded ordinary `Player`; fake connection и client packet simulation запрещены.
- Все действия идут через checked domain gateways; direct HP/quest/reward mutation запрещена.
- Общий budgeted scheduler; никаких thread/future на AI.
- Настоящие journal, inventory, equipment, skills и currency остаются единственным
  источником истины.
- Все AI flags default-off; allowlists узкие; любое расхождение отклоняется fail-closed.
- Runtime-копия template Cool не сохраняется; account Test остаётся deactivated.

## Последний принятый шаг

2F — explicit, idempotent create и ownership постоянного PERSONAL_COMPANION как
настоящего level-1 `Player` на закрытом AI-host account, вне character slots
owner. Реальный клиент подтвердил один и тот же `player_id=106124` для repeat
create, dismiss/summon, follow, relogin и полного restart; foreign owner был
отклонён, SQL подтвердил один claim/binding/body. Решение и scope:
[ADR-0006](ADR-0006-PRODUCT-COMPANION-CREATION.md) и
[IMPLEMENTATION_PLAN_2F](IMPLEMENTATION_PLAN_2F.md).

Продуктовая Веха 1 закрыта как вертикальный slice: создание, follow/wait,
quest-goal/execution, owner attribution и persistence подтверждены. Этап 3 ещё
не начат.

### 2G — Navigation Hardening

`PERSONAL_COMPANION` сохраняет основной 2A follow contract, но теперь имеет
структурированные причины movement rejection и диагностируемые retry/recovery
status. На близкой дистанции follow пробует ограниченный local recovery через
`REAR`, `REAR_LEFT`, `REAR_RIGHT`. При значительном отставании он хранит только
runtime bounded owner breadcrumb trail: sampling начинается сразу в FOLLOWING,
ограничен entries/age/path length, а map/instance/time/неправдоподобные
spatial discontinuity fail-closed разрывают continuity.

Каждый breadcrumb остаётся лишь navigation hint: segment проходит обычные
map/instance, geodata/Z, collision, LoS и start-revalidation проверки. Если
первая breadcrumb небезопасна из-за локального world-prop/geodata seam,
разрешён только bounded chronological look-ahead из трёх точек; более поздняя
точка принимается исключительно после собственного полного gateway check.
Teleport, direct position mutation и universal pathfinding отсутствуют.

Для ground FOLLOWING доступен transient server-controlled locomotion override:
минимум native speed companion и current effective ground speed owner, плюс
малый bounded catch-up при большом separation. Он не меняет `GameStats`, buff,
item, equipment или DB, не применяется connected player, запрещён при native
movement restriction/flight/glide/ride и временно подавляется принятым combat
signal. Это не combat mobility.

Ручная 2G-приёмка подтвердила ordinary follow, speed-scroll owner, bounded
catch-up, hill/curved-road breadcrumbs, отсутствие false-positive `Z_JUMP`,
безопасный look-ahead вокруг world geometry и самостоятельное догоняние без
teleport/direct mutation/pathfinding.

Принято следующее продуктовое направление для PERSONAL_COMPANION: character
level будет синхронизирован с owner 1:1, без самостоятельного XP и второй native
reward share; gear не зеркалируется, а inventory/equipment остаются отдельным
реальным состоянием без копирования предметов. Это ещё не реализовано и не имеет
технического ADR: прежде нужен отдельный аудит native level/skills/inventory и
account-scoped mechanics.

## Наблюдения для следующих slices

- Universal pathfinding/navmesh отсутствует. 2G безопасно harden'ит только
  direct segments, local offsets и bounded owner breadcrumbs; disconnected
  companion без пригодного trail остаётся fail-closed BLOCKED.
- Flight, glide, ride/mount и специальные movement modes не имеют catch-up
  support.
- Combat status сворачивает конкретный quest authorization reason в общий
  `TARGET_NOT_ALLOWED`; отдельный spawn template `210133` был безопасно отклонён
  как отсутствующий в immutable manifest.
- Один shared deactivated host account допустим для текущего starter state, но
  перед реальной inventory/economy progression нужен отдельный аудит
  account-scoped warehouse/state.
- Legacy 2E binding без creation claim при включённом 2F намеренно не
  усыновляется; это fail-closed migration boundary.

  ### Platform direction

Aion остаётся самостоятельным продуктом и первой reference-интеграцией
будущего game-agnostic Living World Engine.

Новые системы по возможности классифицируются как:

- `AION_ADAPTER_SPECIFIC`;
- `MMO_DOMAIN_REUSABLE`;
- `LIVING_WORLD_CORE_REUSABLE`.

Это пока не означает физическое разделение репозитория.

Aion-specific authoritative mechanics остаются внутри Aion integration layer.

Living World Core и domain modules не должны находиться на realtime gameplay
hot path. Movement/combat/skills/collision/geodata и другие latency-sensitive
действия выполняются локально game-specific controllers/adapters.

Adapter должен быть локально deployable с игровым сервером; его конкретная
форма может быть in-process plugin/module, server resource или thin integration
shim + local sidecar.

Текущая модель лицензирования Adapter не зафиксирована.

## Продуктовый курс, подтверждённый 2026-11-06 (не реализован)

Помимо прежней цели полезного компаньона и AI-жителей принят курс
**Living World + Autonomous Society**: автономные мировые события, последствия
поступков, личная/общественная память, персонажи со своими целями и связями,
легионы со своими интересами, союзы/войны/осады и значимая свобода игрока.
Персональный компаньон становится спутником личной истории и уважает выбор
владельца. Игровая боевая система и массовые осады Aion сохраняются.

**Статус технических решений:** Proposed. Новые docs:
[LIVING_WORLD_ARCHITECTURE](LIVING_WORLD_ARCHITECTURE.md),
[COMPANION_STORY_PARTNER_SPEC](COMPANION_STORY_PARTNER_SPEC.md),
[AUTONOMOUS_SOCIETY_SPEC](AUTONOMOUS_SOCIETY_SPEC.md),
[IMPLEMENTATION_PLAN_LW](IMPLEMENTATION_PLAN_LW.md) и ADR-0007…0009.

Достижения C1–C8 не переписываются; полная клиентская приёмка не завершена.
Добавлен только узкий W0 с одним типом личной памяти. Off-screen simulation,
политические легионы и cross-faction alliances не реализованы.
