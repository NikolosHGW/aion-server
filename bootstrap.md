## Запуск

```bash
docker compose -f docker-compose.db.yml -f docker-compose.servers.yml up -d
```

Контейнеры и клиентские TCP-порты:

- `aion-login`: `2106`
- `aion-game`: `7777`
- `aion-chat`: `10241`

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
