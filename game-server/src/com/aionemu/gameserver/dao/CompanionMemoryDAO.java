package com.aionemu.gameserver.dao;

import java.sql.*;
import java.util.*;

import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.gameserver.services.ai.memory.*;
import static com.aionemu.gameserver.services.ai.memory.NativeQuestCompletionReader.query;

/** One W0 row atomically contains its source event and owner-only projection (version 0 -> 1).
 * No native table is written. Source keys/tombstones are intentionally retained across character deletion:
 * a reused numeric ID or a replacement claim cannot inherit/replay the old episode. */
public final class CompanionMemoryDAO implements CompanionMemoryStore {
	public static final long TTL_MILLIS = 86_400_000;
	public static final int MAX_ATTEMPTS = 256;

	@Override public boolean saveCandidate(QuestCompletionEvidence evidence) throws SQLException {
		try (var connection = DatabaseFactory.getConnection()) {
			connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
			connection.setAutoCommit(false);
			try {
				try (var statement = query(connection, """
					INSERT INTO ai_companion_memories (realm,owner_player_id,quest_id,event_id,companion_player_id,claim_token,
					complete_count,completed_second,observed_millis,session_generation,map_id,instance_id,witness_distance,next_attempt_millis)
					VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE owner_player_id=owner_player_id
					""")) {
					statement.setString(1, evidence.realm()); statement.setInt(2, evidence.ownerId()); statement.setInt(3, evidence.questId());
					statement.setString(4, UUID.randomUUID().toString()); statement.setInt(5, evidence.companionId());
					statement.setString(6, evidence.claimToken().toString()); statement.setInt(7, evidence.completeCount());
					statement.setLong(8, evidence.completedSecond()); statement.setLong(9, evidence.observedMillis());
					statement.setLong(10, evidence.sessionGeneration()); statement.setInt(11, evidence.mapId());
					statement.setInt(12, evidence.instanceId()); statement.setDouble(13, evidence.distance());
					statement.setLong(14, evidence.observedMillis()); statement.executeUpdate();
				}
				boolean same;
				try (var statement = keyed(connection, "SELECT * FROM ai_companion_memories WHERE realm=? AND owner_player_id=? AND quest_id=? FOR UPDATE", evidence)) {
					try (var row = statement.executeQuery()) {
						if (!row.next()) throw new SQLException("W0 source key absent after insert");
						same = evidence.sameOccurrence(readEvidence(row));
					}
				}
				if (!same) {
					try (var statement = keyed(connection, """
						UPDATE ai_companion_memories SET state='QUARANTINED',reason='PAYLOAD_CONFLICT'
						WHERE realm=? AND owner_player_id=? AND quest_id=? AND state='PENDING'
						""", evidence)) { statement.executeUpdate(); }
				}
				connection.commit(); return same;
			} catch (Exception failure) { connection.rollback(); throw failure; }
		}
	}

	@Override public int reconcile(String realm, long now, int limit) throws SQLException {
		if (!QuestCompletionEvidence.validRealm(realm) || limit < 1 || limit > 32) throw new IllegalArgumentException("Invalid W0 batch");
		var owners = new ArrayList<Integer>();
		try (var connection = DatabaseFactory.getConnection(); var statement = query(connection, """
			SELECT owner_player_id FROM ai_companion_memories WHERE realm=? AND state='PENDING' AND next_attempt_millis<=?
			ORDER BY next_attempt_millis,owner_player_id LIMIT ?
			""")) {
			statement.setString(1, realm); statement.setLong(2, now); statement.setInt(3, limit);
			try (var rows = statement.executeQuery()) { while (rows.next()) owners.add(rows.getInt(1)); }
		}
		int confirmed = 0;
		for (int owner : owners) if (reconcileOne(realm, owner, now)) confirmed++;
		return confirmed;
	}

	private boolean reconcileOne(String realm, int owner, long now) throws SQLException {
		try (var connection = DatabaseFactory.getConnection()) {
			connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
			connection.setAutoCommit(false);
			try {
				QuestCompletionEvidence evidence;
				int attempts;
				try (var statement = query(connection, """
					SELECT * FROM ai_companion_memories WHERE realm=? AND owner_player_id=? AND quest_id=1102 FOR UPDATE
					""")) {
					statement.setString(1, realm); statement.setInt(2, owner);
					try (var row = statement.executeQuery()) {
						if (!row.next() || !"PENDING".equals(row.getString("state")) || row.getLong("next_attempt_millis") > now) {
							connection.rollback(); return false;
						}
						evidence = readEvidence(row); attempts = row.getInt("attempts") + 1;
					}
				}
				String state, reason;
				if (now - evidence.observedMillis() >= TTL_MILLIS || attempts > MAX_ATTEMPTS) {
					state = "EXPIRED"; reason = "CONFIRMATION_DEADLINE";
				} else {
					var nativeResult = NativeQuestCompletionReader.read(connection, evidence);
					reason = nativeResult.name();
					state = switch (nativeResult) {
						case MATCH -> "CONFIRMED";
						case WAITING -> "PENDING";
						case IDENTITY_CONFLICT, SOURCE_CONFLICT -> "QUARANTINED";
					};
				}
				try (var statement = query(connection, """
					UPDATE ai_companion_memories SET state=?,reason=?,attempts=?,next_attempt_millis=?,projection_version=?,confirmed_millis=?
					WHERE realm=? AND owner_player_id=? AND quest_id=1102 AND state='PENDING'
					""")) {
					boolean confirmed = state.equals("CONFIRMED");
					statement.setString(1, state); statement.setString(2, reason); statement.setInt(3, attempts);
					statement.setLong(4, now + Math.min(300_000L, 5_000L << Math.min(attempts, 6)));
					statement.setInt(5, confirmed ? 1 : 0);
					if (confirmed) statement.setLong(6, now); else statement.setNull(6, Types.BIGINT);
					statement.setString(7, realm); statement.setInt(8, owner);
					if (statement.executeUpdate() != 1) throw new SQLException("W0 transition lost source ownership");
				}
				connection.commit(); return state.equals("CONFIRMED");
			} catch (Exception failure) { connection.rollback(); throw failure; }
		}
	}

	/** A SELECT only: no reconciliation, creation, writes or native rewards from status. */
	@Override public String status(String realm, int ownerId, int accountId) throws SQLException {
		if (!QuestCompletionEvidence.validRealm(realm) || ownerId <= 0 || accountId <= 0) throw new IllegalArgumentException("Invalid memory reader");
		try (var connection = DatabaseFactory.getConnection(); var statement = query(connection, """
			SELECT m.state,m.projection_version FROM ai_companion_memories m
			JOIN players p ON p.id=m.owner_player_id AND p.account_id=?
			JOIN ai_companion_creation_claims c ON c.owner_player_id=m.owner_player_id
			 AND c.claim_token=m.claim_token AND c.companion_player_id=m.companion_player_id
			JOIN ai_companions b ON b.owner_player_id=m.owner_player_id AND b.companion_player_id=m.companion_player_id
			 AND b.role='PERSONAL_COMPANION'
			WHERE m.realm=? AND m.owner_player_id=? AND m.quest_id=1102
			""")) {
			statement.setInt(1, accountId); statement.setString(2, realm); statement.setInt(3, ownerId);
			try (var row = statement.executeQuery()) {
				if (!row.next()) return "memory=EMPTY";
				if ("CONFIRMED".equals(row.getString("state")) && row.getInt("projection_version") == 1)
					return "memory=CONFIRMED: Владелец завершил квест 1102; спутник присутствовал при сдаче.";
				return "memory=" + row.getString("state");
			}
		}
	}

	private static PreparedStatement keyed(Connection connection, String sql, QuestCompletionEvidence evidence) throws SQLException {
		var statement = query(connection, sql);
		statement.setString(1, evidence.realm()); statement.setInt(2, evidence.ownerId()); statement.setInt(3, evidence.questId());
		return statement;
	}
	private static QuestCompletionEvidence readEvidence(ResultSet row) throws SQLException {
		return new QuestCompletionEvidence(row.getString("realm"), row.getInt("owner_player_id"), row.getInt("companion_player_id"),
			UUID.fromString(row.getString("claim_token")), row.getInt("quest_id"), row.getInt("complete_count"), row.getLong("completed_second"),
			row.getLong("observed_millis"), row.getLong("session_generation"), row.getInt("map_id"), row.getInt("instance_id"), row.getDouble("witness_distance"));
	}
}
