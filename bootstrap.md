## Запуск

```bash
docker compose -f docker-compose.db.yml -f docker-compose.servers.yml up -d --build
```

Контейнеры и клиентские TCP-порты:

- `aion-login`: `2106`
- `aion-game`: `7777`
- `aion-chat`: `10241`

Разработка AI продолжается в `integration/ai-4.8`; `feature/ai-mvp` сохранена
для истории и не изменяется. Для этого этапа используем чистую базу: при новом
Docker volume начальный скрипт импортирует полные SQL-схемы из репозитория,
включая таблицы AI. `update.sql` поверх новой схемы запускать не нужно.
Существующий volume автоматически не пересоздаётся и не обновляется.
После создания новых аккаунтов настройте AI-host/template ID в локальном
Docker-профиле: старые экспериментальные ID не относятся к пустой базе.
Подробности: [интеграция 4.8](docs/ai/UPSTREAM_INTEGRATION_2026-11-04.md).

MySQL (`3306`) опубликован только на `127.0.0.1` и не должен открываться в интернет.

## Подключение к серверу

Game- и chat-сервер настроены для доступа только с этого компьютера через `127.0.0.1`.
Запускай клиент так:

```bat
start /affinity 7FFFFFFF "" "bin64\AION.bin" -ip:127.0.0.1 -port:2106 -cc:2 -lang:ENG -loginex
```

Аккаунты создаются автоматически: можно использовать новый логин и пароль.

Порты Aion опубликованы Docker только на `127.0.0.1`, поэтому подключения из
локальной сети и интернета невозможны. Для публикации сервера потребуется
вернуть публичные адреса и привязки портов к `0.0.0.0`.

## Проверка и логи

```bash
docker compose -f docker-compose.db.yml -f docker-compose.servers.yml ps
docker logs -f aion-game
docker logs -f aion-login
docker logs -f aion-chat
```

После изменения конфигурации серверов:

```bash
docker compose -f docker-compose.db.yml -f docker-compose.servers.yml restart chat game
```

Остановить сервер:

```bash
docker compose -f docker-compose.db.yml -f docker-compose.servers.yml stop
```
