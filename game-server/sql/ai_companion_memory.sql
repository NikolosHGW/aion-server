-- W0: one witnessed native quest-1102 completion per numeric owner ID and stable realm.
-- Deliberately no cascading FKs: keep source tombstones across deletion/ID reuse.
-- Requires the current, unreleased creation schema with claim_token; use a fresh dev DB.
CREATE TABLE IF NOT EXISTS `ai_companion_memories` (
  `realm` varchar(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `owner_player_id` int NOT NULL,
  `quest_id` int NOT NULL,
  `event_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `companion_player_id` int NOT NULL,
  `claim_token` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `complete_count` int NOT NULL,
  `completed_second` bigint NOT NULL,
  `observed_millis` bigint NOT NULL,
  `session_generation` bigint NOT NULL,
  `map_id` int NOT NULL,
  `instance_id` int NOT NULL,
  `witness_distance` double NOT NULL,
  `state` enum('PENDING','CONFIRMED','QUARANTINED','EXPIRED') NOT NULL DEFAULT 'PENDING',
  `reason` varchar(32) NOT NULL DEFAULT 'AWAITING_NATIVE_SAVE',
  `attempts` int NOT NULL DEFAULT 0,
  `next_attempt_millis` bigint NOT NULL,
  `projection_version` int NOT NULL DEFAULT 0,
  `confirmed_millis` bigint DEFAULT NULL,
  PRIMARY KEY (`realm`,`owner_player_id`,`quest_id`),
  UNIQUE KEY `ai_companion_memories_event_unique` (`event_id`),
  KEY `ai_companion_memories_pending` (`realm`,`state`,`next_attempt_millis`,`owner_player_id`),
  CONSTRAINT `ai_companion_memories_projection` CHECK (
    (`state`='CONFIRMED' AND `projection_version`=1 AND `confirmed_millis` IS NOT NULL)
    OR (`state`<>'CONFIRMED' AND `projection_version`=0 AND `confirmed_millis` IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
