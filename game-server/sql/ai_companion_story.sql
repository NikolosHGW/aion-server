-- P1: one explicit suggestion/decision per owner and realm. This is not a quest journal or action intent.
-- Retained across owner deletion; access requires the current claim and confirmed W0 episode.
CREATE TABLE IF NOT EXISTS `ai_companion_story` (
  `realm` varchar(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `owner_player_id` int NOT NULL,
  `companion_player_id` int NOT NULL,
  `claim_token` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `source_event_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `quest_id` int NOT NULL,
  `offer_token` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `semantic_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `decision` enum('OFFERED','DECLINED','RESET') NOT NULL,
  `revision` bigint NOT NULL,
  PRIMARY KEY (`realm`,`owner_player_id`),
  UNIQUE KEY `ai_companion_story_offer_unique` (`offer_token`),
  CONSTRAINT `ai_companion_story_scope` CHECK (`quest_id`=1120 AND `revision`>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
