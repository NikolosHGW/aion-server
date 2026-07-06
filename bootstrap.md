Сейчас живые контейнеры:
aion-mysql   3306
aion-login   2106
aion-game    7777
aion-chat    10241
Порты на 127.0.0.1 тоже открыты: 2106, 7777, 10241, 3306.
Логи хорошие: game-server зарегистрировался в login-server, подключился к chat-server, и пишет:
Game server started
Connected to login server
Connected to chat server
Gameserver #1 is now online
Запускай клиент так:
start /affinity 7FFFFFFF "" "bin64\AION.bin" -ip:127.0.0.1 -port:2106 -cc:2 -lang:ENG -loginex
Аккаунты включены на autocreate, то есть можно пробовать любой новый логин/пароль.
Я добавил/поправил Docker-файлы:
.dockerignore
Dockerfile.server
docker-compose.db.yml
docker-compose.servers.yml
docker/mysql/init/01-init-aion.sh
docker/server-config/*/my*.properties
Полезные команды:
docker compose -f docker-compose.db.yml -f docker-compose.servers.yml ps
docker logs -f aion-game
docker logs -f aion-login
docker logs -f aion-chat
Остановить:
docker compose -f docker-compose.db.yml -f docker-compose.servers.yml stop
Запустить снова:
docker compose -f docker-compose.db.yml -f docker-compose.servers.yml up -d
