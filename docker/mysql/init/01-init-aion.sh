#!/bin/sh
set -e

mysql -uroot <<'SQL'
CREATE DATABASE IF NOT EXISTS aion_ls CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS aion_gs CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS aion_cs CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
SQL

mysql -uroot aion_ls < /aion-server/login-server/sql/aion_ls.sql

# The current fresh GS schema has a missing comma before bookmark_ibfk_1.
# Patch it only for import, leaving the repository SQL untouched.
sed 's/PRIMARY KEY (`player_id`, `name`)/PRIMARY KEY (`player_id`, `name`),/' \
  /aion-server/game-server/sql/aion_gs.sql > /tmp/aion_gs.sql
mysql -uroot aion_gs < /tmp/aion_gs.sql

mysql -uroot aion_cs < /aion-server/chat-server/sql/aion_cs.sql

mysql -uroot aion_ls <<'SQL'
INSERT INTO gameservers (id, mask, password)
VALUES (1, '*', '1234')
ON DUPLICATE KEY UPDATE mask = VALUES(mask), password = VALUES(password);
SQL
