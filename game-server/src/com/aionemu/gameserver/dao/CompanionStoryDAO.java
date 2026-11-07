package com.aionemu.gameserver.dao;

import java.sql.*;
import java.util.*;
import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.gameserver.services.ai.story.CompanionStoryStore;
import static com.aionemu.gameserver.services.ai.story.CompanionStory.*;
import static com.aionemu.gameserver.services.ai.memory.NativeQuestCompletionReader.query;

/** P1 writes only conversation decisions. W0 and native state remain read-only. */
public final class CompanionStoryDAO implements CompanionStoryStore {
	private static final String EPISODE = """
		SELECT m.event_id,m.companion_player_id,m.claim_token,m.completed_second
		FROM ai_companion_memories m
		JOIN players p ON p.id=m.owner_player_id AND p.account_id=?
		JOIN ai_companion_creation_claims c ON c.owner_player_id=m.owner_player_id
		 AND c.claim_token=m.claim_token AND c.companion_player_id=m.companion_player_id
		JOIN ai_companions b ON b.owner_player_id=m.owner_player_id AND b.companion_player_id=m.companion_player_id
		 AND b.role='PERSONAL_COMPANION'
		WHERE m.realm=? AND m.owner_player_id=? AND m.quest_id=1102 AND m.state='CONFIRMED' AND m.projection_version=1
		""";
	private Optional<Episode> episode(Connection connection, Owner owner, boolean lock) throws SQLException {
		// Lock the source separately: a joined SELECT alone does not guarantee optimizer lock order.
		if (lock) {
			try (var statement=query(connection,"SELECT event_id FROM ai_companion_memories WHERE realm=? AND owner_player_id=? AND quest_id=1102 FOR UPDATE")) {
				statement.setString(1,owner.realm()); statement.setInt(2,owner.playerId());
				try (var row=statement.executeQuery()) { if (!row.next()) return Optional.empty(); }
			}
		}
		try (var statement=query(connection, EPISODE + (lock ? " LOCK IN SHARE MODE" : ""))) {
			statement.setInt(1,owner.accountId()); statement.setString(2,owner.realm()); statement.setInt(3,owner.playerId());
			try (var row=statement.executeQuery()) {
				return row.next() ? Optional.of(new Episode(UUID.fromString(row.getString(1)),row.getInt(2),
					UUID.fromString(row.getString(3)),row.getLong(4))) : Optional.empty();
			}
		}
	}
	private Offer offerRow(Connection connection, Owner owner) throws SQLException {
		try (var statement=query(connection,"SELECT * FROM ai_companion_story WHERE realm=? AND owner_player_id=?")) {
			statement.setString(1,owner.realm()); statement.setInt(2,owner.playerId());
			try (var row=statement.executeQuery()) {
				if (!row.next()) return null;
				if (row.getInt("quest_id") != QUEST) throw new SQLException("Unknown P1 suggestion");
				return new Offer(UUID.fromString(row.getString("offer_token")),UUID.fromString(row.getString("source_event_id")),
					row.getInt("companion_player_id"),UUID.fromString(row.getString("claim_token")),row.getString("semantic_hash"),
					Decision.valueOf(row.getString("decision")),row.getLong("revision"));
			}
		}
	}
	@Override public Optional<View> read(Owner owner) throws SQLException {
		// Consistent read snapshot, without locks or writes. Does not confirm a pending W0 event.
		try (var connection=DatabaseFactory.getConnection()) {
			connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); connection.setAutoCommit(false);
			try {
				var episode=episode(connection,owner,false);
				var result=episode.isEmpty() ? Optional.<View>empty() : Optional.of(new View(episode.get(),offerRow(connection,owner)));
				connection.rollback(); return result;
			} catch (Exception failure) { connection.rollback(); throw failure; }
		}
	}
	@Override public Offer offer(Actor actor, Episode expected, String fingerprint) throws SQLException {
		// Same lock order as W0: memory source, claim/binding, then our new row.
		try (var connection=DatabaseFactory.getConnection()) {
			connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); connection.setAutoCommit(false);
			try {
				Episode actual=episode(connection,actor.owner(),true).orElseThrow(() -> new IllegalStateException("No confirmed shared episode"));
				if (!actual.equals(expected) || !actual.belongsTo(actor)) throw new IllegalStateException("Story source/ownership changed");
				var view=new View(actual,offerRow(connection,actor.owner()));
				Offer old=view.offer();
				if (old != null && (old.decision()==Decision.DECLINED || old.decision()==Decision.OFFERED && old.fingerprint().equals(fingerprint))) {
					connection.rollback(); return old;
				}
				Offer next=new Offer(UUID.randomUUID(),actual.event(),actual.bodyId(),actual.claim(),fingerprint,Decision.OFFERED,
					old==null ? 1 : Math.addExact(old.revision(),1));
				try (var statement=query(connection,"""
					INSERT INTO ai_companion_story (realm,owner_player_id,companion_player_id,claim_token,source_event_id,
					 quest_id,offer_token,semantic_hash,decision,revision)
					VALUES (?,?,?,?,?,1120,?,?,?,?) ON DUPLICATE KEY UPDATE
					 offer_token=VALUES(offer_token),semantic_hash=VALUES(semantic_hash),decision=VALUES(decision),revision=VALUES(revision)
					""")) {
					statement.setString(1,actor.owner().realm()); statement.setInt(2,actor.owner().playerId()); statement.setInt(3,next.bodyId());
					statement.setString(4,next.claim().toString()); statement.setString(5,next.event().toString());
					statement.setString(6,next.token().toString()); statement.setString(7,next.fingerprint());
					statement.setString(8,next.decision().name()); statement.setLong(9,next.revision()); statement.executeUpdate();
				}
				connection.commit(); return next;
			} catch (Exception failure) { connection.rollback(); throw failure; }
		}
	}
	@Override public Optional<Offer> decide(Actor actor, UUID token, Decision decision) throws SQLException {
		if (token==null || decision==null || decision==Decision.OFFERED) throw new IllegalArgumentException("Invalid P1 decision");
		try (var connection=DatabaseFactory.getConnection()) {
			connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); connection.setAutoCommit(false);
			try {
				var episode=episode(connection,actor.owner(),true);
				if (episode.isEmpty() || !episode.get().belongsTo(actor)) { connection.rollback(); return Optional.empty(); }
				Offer old=new View(episode.get(),offerRow(connection,actor.owner())).offer();
				if (old==null || !old.token().equals(token) || old.decision()==Decision.RESET && decision!=Decision.RESET) {
					connection.rollback(); return Optional.empty();
				}
				if (old.decision()==decision) { connection.rollback(); return Optional.of(old); }
				long revision=Math.addExact(old.revision(),1);
				try (var statement=query(connection,"""
					UPDATE ai_companion_story SET decision=?,revision=?
					WHERE realm=? AND owner_player_id=? AND offer_token=? AND revision=?
					""")) {
					statement.setString(1,decision.name()); statement.setLong(2,revision); statement.setString(3,actor.owner().realm());
					statement.setInt(4,actor.owner().playerId()); statement.setString(5,token.toString()); statement.setLong(6,old.revision());
					if (statement.executeUpdate()!=1) throw new SQLException("P1 decision lost ownership");
				}
				connection.commit();
				return Optional.of(new Offer(old.token(),old.event(),old.bodyId(),old.claim(),old.fingerprint(),decision,revision));
			} catch (Exception failure) { connection.rollback(); throw failure; }
		}
	}
}
