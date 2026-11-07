package com.aionemu.gameserver.services.ai.memory;

import java.sql.*;

/** Exception-aware native read gateway. Shared locks survive until the memory transaction commits. */
public final class NativeQuestCompletionReader {
	public enum Result { MATCH, WAITING, IDENTITY_CONFLICT, SOURCE_CONFLICT }
	private NativeQuestCompletionReader() { }

	public static PreparedStatement query(Connection connection, String sql) throws SQLException {
		var statement = connection.prepareStatement(sql);
		statement.setQueryTimeout(2);
		return statement;
	}

	public static Result read(Connection connection, QuestCompletionEvidence evidence) throws SQLException {
		try (var statement = query(connection, """
			SELECT c.claim_token, c.companion_player_id, b.companion_player_id AS bound_body, b.role
			FROM ai_companion_creation_claims c JOIN ai_companions b ON b.owner_player_id=c.owner_player_id
			JOIN players p ON p.id=c.owner_player_id JOIN players body ON body.id=c.companion_player_id
			WHERE c.owner_player_id=? LOCK IN SHARE MODE
			""")) {
			statement.setInt(1, evidence.ownerId());
			try (var row = statement.executeQuery()) {
				if (!row.next() || !evidence.claimToken().toString().equals(row.getString("claim_token"))
					|| row.getInt("companion_player_id") != evidence.companionId() || row.getInt("bound_body") != evidence.companionId()
					|| !"PERSONAL_COMPANION".equals(row.getString("role"))) return Result.IDENTITY_CONFLICT;
			}
		}
		try (var statement = query(connection, """
			SELECT status, complete_count, complete_time FROM player_quests
			WHERE player_id=? AND quest_id=? LOCK IN SHARE MODE
			""")) {
			statement.setInt(1, evidence.ownerId()); statement.setInt(2, evidence.questId());
			try (var row = statement.executeQuery()) {
				if (!row.next() || !"COMPLETE".equals(row.getString("status"))) return Result.WAITING;
				return row.getInt("complete_count") == evidence.completeCount()
					&& QuestCompletionEvidence.second(row.getTimestamp("complete_time")) == evidence.completedSecond()
					? Result.MATCH : Result.SOURCE_CONFLICT;
			}
		}
	}
}
