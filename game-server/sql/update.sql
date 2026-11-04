/*
 * DB changes since a31c1df (29.09.2026)
 */

UPDATE `player_skills` SET `skill_level` = 1 WHERE (`skill_id` < 30000 OR `skill_id` = 40009);

ALTER TABLE `players`
	ADD COLUMN `pvp_exp` bigint NOT NULL DEFAULT '0' AFTER `reposte_energy`;

ALTER TABLE `player_recipes`
	ADD COLUMN `production_limit` tinyint NOT NULL DEFAULT '0' AFTER `recipe_id`;

ALTER TABLE `player_appearance`
	RENAME COLUMN `jaw_height` TO `chin_height`,
	RENAME COLUMN `chin_jut` TO `cheek_bones`;

ALTER TABLE `house_scripts`
	ADD COLUMN `script_data` blob NULL AFTER `script_id`,
	ADD COLUMN `uncompressed_size` int NOT NULL DEFAULT '0' AFTER `script_data`;
UPDATE `house_scripts` SET
	`uncompressed_size` = LENGTH(CONVERT(`script` USING utf16le)),
	`script_data` = SUBSTRING(COMPRESS(CAST(CONVERT(`script` USING utf16le) AS BINARY)), 5);
ALTER TABLE `house_scripts`
	DROP COLUMN `script`,
	CHANGE COLUMN `script_data` `script` blob NOT NULL,
	ALTER COLUMN `uncompressed_size` DROP DEFAULT;

-- AI companion extension (additive).
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
