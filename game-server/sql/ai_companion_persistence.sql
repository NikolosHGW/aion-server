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
