/*
* DB changes since f2f77fe (15.05.2026)
 */

DELETE FROM inventory WHERE item_id IN (182007170, 188100252, 188100253, 188100254, 188100255, 188100256);

ALTER TABLE `bookmark`
	CHANGE COLUMN `char_id` `player_id` INT NOT NULL FIRST,
	CHANGE COLUMN `name` `name` VARCHAR(27) NOT NULL AFTER `player_id`,
	CHANGE COLUMN `world_id` `world_id` INT NOT NULL AFTER `name`,
	DROP COLUMN `id`,
	DROP PRIMARY KEY,
	ADD PRIMARY KEY (`player_id`, `name`),
	ADD CONSTRAINT `bookmark_ibfk_1` FOREIGN KEY (`player_id`) REFERENCES `players` (`id`) ON DELETE CASCADE ON UPDATE CASCADE;

DROP TABLE `ingameshop`;
DROP TABLE `ingameshop_log`;

CREATE TABLE IF NOT EXISTS `ai_companions` (
	`owner_player_id` int NOT NULL,
	`companion_player_id` int NOT NULL,
	`role` varchar(32) NOT NULL,
	`created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
	`updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
	PRIMARY KEY (`owner_player_id`),
	UNIQUE KEY `ai_companions_companion_unique` (`companion_player_id`),
	CONSTRAINT `ai_companions_owner_fk` FOREIGN KEY (`owner_player_id`) REFERENCES `players` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
	CONSTRAINT `ai_companions_companion_fk` FOREIGN KEY (`companion_player_id`) REFERENCES `players` (`id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `ai_companion_goals` (
	`owner_player_id` int NOT NULL,
	`goal_type` varchar(32) NOT NULL,
	`target_id` int NOT NULL,
	`plan_version` smallint unsigned NOT NULL,
	`semantic_fingerprint` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
	`selected_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
	`updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
	PRIMARY KEY (`owner_player_id`),
	CONSTRAINT `ai_companion_goals_owner_fk` FOREIGN KEY (`owner_player_id`) REFERENCES `ai_companions` (`owner_player_id`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `ai_companion_creation_claims` (
	`owner_player_id` int NOT NULL,
	`body_name` varchar(50) NOT NULL,
	`host_account_id` int NOT NULL,
	`host_account_name` varchar(50) NOT NULL,
	`companion_player_id` int DEFAULT NULL,
	`created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
	`updated_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
	PRIMARY KEY (`owner_player_id`),
	UNIQUE KEY `ai_companion_creation_claims_name_unique` (`body_name`),
	UNIQUE KEY `ai_companion_creation_claims_companion_unique` (`companion_player_id`),
	CONSTRAINT `ai_companion_creation_claims_owner_fk` FOREIGN KEY (`owner_player_id`) REFERENCES `players` (`id`) ON DELETE CASCADE ON UPDATE CASCADE,
	CONSTRAINT `ai_companion_creation_claims_companion_fk` FOREIGN KEY (`companion_player_id`) REFERENCES `players` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
