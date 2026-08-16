package com.aionemu.gameserver.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.gameserver.services.ai.persistence.CompanionBinding;
import com.aionemu.gameserver.services.ai.persistence.CompanionPersistenceException;
import com.aionemu.gameserver.services.ai.persistence.CompanionPersistenceRepository;
import com.aionemu.gameserver.services.ai.persistence.CompanionPersistentState;
import com.aionemu.gameserver.services.ai.persistence.PersistedGoalIntent;

public final class CompanionPersistenceDAO implements CompanionPersistenceRepository {

	private static final String LOAD_QUERY = """
		SELECT c.owner_player_id, c.companion_player_id, c.role,
		       g.goal_type, g.target_id, g.plan_version, g.semantic_fingerprint
		FROM ai_companions c
		LEFT JOIN ai_companion_goals g ON g.owner_player_id = c.owner_player_id
		WHERE c.owner_player_id = ?
		""";
	private static final String UPSERT_BINDING_QUERY = """
		INSERT INTO ai_companions (owner_player_id, companion_player_id, role)
		VALUES (?, ?, ?)
		ON DUPLICATE KEY UPDATE companion_player_id = VALUES(companion_player_id), role = VALUES(role)
		""";
	private static final String UPSERT_GOAL_QUERY = """
		INSERT INTO ai_companion_goals
		(owner_player_id, goal_type, target_id, plan_version, semantic_fingerprint)
		VALUES (?, ?, ?, ?, ?)
		ON DUPLICATE KEY UPDATE goal_type = VALUES(goal_type), target_id = VALUES(target_id),
		plan_version = VALUES(plan_version), semantic_fingerprint = VALUES(semantic_fingerprint)
		""";
	private static final String DELETE_GOAL_QUERY = """
		DELETE g FROM ai_companion_goals g
		JOIN ai_companions c ON c.owner_player_id = g.owner_player_id
		WHERE g.owner_player_id = ? AND c.companion_player_id = ?
		""";

	@Override
	public Optional<CompanionPersistentState> load(int ownerPlayerId) {
		if (ownerPlayerId <= 0)
			throw new IllegalArgumentException("Owner player ID must be positive");
		try (Connection connection = DatabaseFactory.getConnection(); PreparedStatement statement = connection.prepareStatement(LOAD_QUERY)) {
			statement.setInt(1, ownerPlayerId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next())
					return Optional.empty();
				CompanionBinding binding = new CompanionBinding(result.getInt("owner_player_id"), result.getInt("companion_player_id"),
					result.getString("role"));
				String goalType = result.getString("goal_type");
				Optional<PersistedGoalIntent> goal = goalType == null ? Optional.empty()
					: Optional.of(new PersistedGoalIntent(binding.ownerPlayerId(), goalType, result.getInt("target_id"),
						result.getInt("plan_version"), result.getString("semantic_fingerprint")));
				if (result.next())
					throw new SQLException("Multiple persistent companion rows for one owner");
				return Optional.of(new CompanionPersistentState(binding, goal));
			}
		} catch (SQLException | RuntimeException e) {
			throw new CompanionPersistenceException("Could not load persistent companion state for owner " + ownerPlayerId, e);
		}
	}

	@Override
	public void saveChosenGoal(CompanionBinding binding, PersistedGoalIntent goalIntent) {
		if (binding == null || goalIntent == null || binding.ownerPlayerId() != goalIntent.ownerPlayerId())
			throw new IllegalArgumentException("Binding and goal intent must have the same owner");
		try (Connection connection = DatabaseFactory.getConnection()) {
			connection.setAutoCommit(false);
			try {
				try (PreparedStatement statement = connection.prepareStatement(UPSERT_BINDING_QUERY)) {
					statement.setInt(1, binding.ownerPlayerId());
					statement.setInt(2, binding.companionPlayerId());
					statement.setString(3, binding.role());
					statement.executeUpdate();
				}
				try (PreparedStatement statement = connection.prepareStatement(UPSERT_GOAL_QUERY)) {
					statement.setInt(1, goalIntent.ownerPlayerId());
					statement.setString(2, goalIntent.goalType());
					statement.setInt(3, goalIntent.targetId());
					statement.setInt(4, goalIntent.planVersion());
					statement.setString(5, goalIntent.semanticFingerprint());
					statement.executeUpdate();
				}
				connection.commit();
			} catch (SQLException | RuntimeException e) {
				rollback(connection, e);
				throw e;
			}
		} catch (SQLException | RuntimeException e) {
			throw new CompanionPersistenceException("Could not persist chosen companion goal for owner " + binding.ownerPlayerId(), e);
		}
	}

	@Override
	public boolean deleteGoal(int ownerPlayerId, int companionPlayerId) {
		if (ownerPlayerId <= 0 || companionPlayerId <= 0)
			throw new IllegalArgumentException("Owner and companion IDs must be positive");
		try (Connection connection = DatabaseFactory.getConnection(); PreparedStatement statement = connection.prepareStatement(DELETE_GOAL_QUERY)) {
			statement.setInt(1, ownerPlayerId);
			statement.setInt(2, companionPlayerId);
			return statement.executeUpdate() == 1;
		} catch (SQLException | RuntimeException e) {
			throw new CompanionPersistenceException("Could not clear persistent companion goal for owner " + ownerPlayerId, e);
		}
	}

	private static void rollback(Connection connection, Exception failure) {
		try {
			connection.rollback();
		} catch (SQLException rollbackFailure) {
			failure.addSuppressed(rollbackFailure);
		}
	}
}
