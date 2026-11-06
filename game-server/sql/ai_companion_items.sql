
-- C5: completed whole-item handovers only. Pending intents are session-local, never replayed at startup.
CREATE TABLE IF NOT EXISTS `ai_companion_item_transfers` (
  `owner_player_id` int NOT NULL,
  `request_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `companion_player_id` int NOT NULL,
  `item_unique_id` int NOT NULL,
  `to_companion` tinyint(1) NOT NULL,
  `committed_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`owner_player_id`,`request_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
