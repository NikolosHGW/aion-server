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
