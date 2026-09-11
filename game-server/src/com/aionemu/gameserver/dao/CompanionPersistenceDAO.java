package com.aionemu.gameserver.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.gameserver.services.ai.creation.CompanionBodyRecord;
import com.aionemu.gameserver.services.ai.creation.CompanionCreationClaim;
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
	private static final String LOAD_CLAIM_QUERY = """
		SELECT owner_player_id, body_name, host_account_id, host_account_name, companion_player_id
		FROM ai_companion_creation_claims
		WHERE owner_player_id = ?
		""";
	private static final String LOAD_CLAIM_FOR_UPDATE_QUERY = LOAD_CLAIM_QUERY + " FOR UPDATE";
	private static final String INSERT_CLAIM_QUERY = """
		INSERT INTO ai_companion_creation_claims (owner_player_id, body_name, host_account_id, host_account_name)
		VALUES (?, ?, ?, ?)
		""";
	private static final String LOAD_BODY_BY_ID_QUERY = bodyQuery("p.id = ?");
	private static final String LOAD_BODY_BY_NAME_QUERY = bodyQuery("p.name = ?");
	private static final String FINALIZE_CLAIM_QUERY = """
		UPDATE ai_companion_creation_claims
		SET companion_player_id = ?
		WHERE owner_player_id = ? AND body_name = ? AND host_account_id = ? AND host_account_name = ?
		  AND (companion_player_id IS NULL OR companion_player_id = ?)
		""";
	private static final String LOAD_BINDING_FOR_UPDATE_QUERY = """
		SELECT owner_player_id, companion_player_id, role FROM ai_companions WHERE owner_player_id = ? FOR UPDATE
		""";
	private static final String INSERT_BINDING_QUERY = """
		INSERT INTO ai_companions (owner_player_id, companion_player_id, role) VALUES (?, ?, ?)
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
	public Optional<CompanionCreationClaim> loadCreationClaim(int ownerPlayerId) {
		if (ownerPlayerId <= 0)
			throw new IllegalArgumentException("Owner player ID must be positive");
		try (Connection connection = DatabaseFactory.getConnection(); PreparedStatement statement = connection.prepareStatement(LOAD_CLAIM_QUERY)) {
			statement.setInt(1, ownerPlayerId);
			try (ResultSet result = statement.executeQuery()) {
				return result.next() ? Optional.of(readClaim(result)) : Optional.empty();
			}
		} catch (SQLException | RuntimeException e) {
			throw new CompanionPersistenceException("Could not load companion creation claim for owner " + ownerPlayerId, e);
		}
	}

	@Override
	public Optional<CompanionBodyRecord> findCompanionBodyByName(String databaseName) {
		if (databaseName == null || databaseName.isBlank())
			throw new IllegalArgumentException("Companion database name must be present");
		return loadBody(LOAD_BODY_BY_NAME_QUERY, databaseName, "name " + databaseName);
	}

	@Override
	public Optional<CompanionBodyRecord> loadCompanionBody(int companionPlayerId) {
		if (companionPlayerId <= 0)
			throw new IllegalArgumentException("Companion player ID must be positive");
		return loadBody(LOAD_BODY_BY_ID_QUERY, companionPlayerId, "ID " + companionPlayerId);
	}

	@Override
	public void reserveCreation(CompanionCreationClaim claim) {
		if (claim == null || claim.companionPlayerId() != null)
			throw new IllegalArgumentException("A new creation reservation must not contain a companion ID");
		try (Connection connection = DatabaseFactory.getConnection(); PreparedStatement statement = connection.prepareStatement(INSERT_CLAIM_QUERY)) {
			statement.setInt(1, claim.ownerPlayerId());
			statement.setString(2, claim.databaseName());
			statement.setInt(3, claim.hostAccountId());
			statement.setString(4, claim.hostAccountName());
			statement.executeUpdate();
		} catch (SQLException | RuntimeException e) {
			throw new CompanionPersistenceException("Could not reserve companion creation for owner " + claim.ownerPlayerId(), e);
		}
	}

	@Override
	public CompanionBinding finalizeCreation(CompanionCreationClaim expectedClaim, CompanionBodyRecord expectedBody) {
		if (expectedClaim == null || expectedBody == null)
			throw new IllegalArgumentException("Creation claim and companion body are required");
		try (Connection connection = DatabaseFactory.getConnection()) {
			connection.setAutoCommit(false);
			try {
				CompanionCreationClaim claim = loadClaimForUpdate(connection, expectedClaim.ownerPlayerId());
				if (!sameClaim(expectedClaim, claim))
					throw new SQLException("Persistent creation claim changed before finalization");
				CompanionBodyRecord body = loadBody(connection, LOAD_BODY_BY_ID_QUERY, expectedBody.playerId())
					.orElseThrow(() -> new SQLException("Created companion body is missing"));
				if (!body.equals(expectedBody) || !body.standardCreationComplete())
					throw new SQLException("Created companion body is incomplete or changed before finalization");
				try (PreparedStatement statement = connection.prepareStatement(FINALIZE_CLAIM_QUERY)) {
					statement.setInt(1, body.playerId());
					statement.setInt(2, claim.ownerPlayerId());
					statement.setString(3, claim.databaseName());
					statement.setInt(4, claim.hostAccountId());
					statement.setString(5, claim.hostAccountName());
					statement.setInt(6, body.playerId());
					if (statement.executeUpdate() != 1)
						throw new SQLException("Creation claim could not be finalized exactly once");
				}
				CompanionBinding binding = new CompanionBinding(claim.ownerPlayerId(), body.playerId(),
					com.aionemu.gameserver.services.ai.persistence.CompanionGoalRestorePolicy.ROLE_PERSONAL_COMPANION);
				Optional<CompanionBinding> existing = loadBindingForUpdate(connection, binding.ownerPlayerId());
				if (existing.isPresent() && !existing.get().equals(binding))
					throw new SQLException("Owner already has a different companion binding");
				if (existing.isEmpty())
					insertBinding(connection, binding);
				connection.commit();
				return binding;
			} catch (SQLException | RuntimeException e) {
				rollback(connection, e);
				throw e;
			}
		} catch (SQLException | RuntimeException e) {
			throw new CompanionPersistenceException("Could not finalize companion creation for owner " + expectedClaim.ownerPlayerId(), e);
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
	public void saveGoalForExistingBinding(CompanionBinding binding, PersistedGoalIntent goalIntent) {
		if (binding == null || goalIntent == null || binding.ownerPlayerId() != goalIntent.ownerPlayerId())
			throw new IllegalArgumentException("Binding and goal intent must have the same owner");
		try (Connection connection = DatabaseFactory.getConnection()) {
			connection.setAutoCommit(false);
			try {
				Optional<CompanionBinding> existing = loadBindingForUpdate(connection, binding.ownerPlayerId());
				if (existing.isEmpty() || !existing.get().equals(binding))
					throw new SQLException("Exact persistent companion binding is required before goal save");
				upsertGoal(connection, goalIntent);
				connection.commit();
			} catch (SQLException | RuntimeException e) {
				rollback(connection, e);
				throw e;
			}
		} catch (SQLException | RuntimeException e) {
			throw new CompanionPersistenceException("Could not persist goal for existing companion binding " + binding.ownerPlayerId(), e);
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

	private static String bodyQuery(String predicate) {
		return """
			SELECT p.id, p.name, p.account_id, p.account_name, p.race, p.gender, p.player_class, p.online,
			       EXISTS(SELECT 1 FROM player_appearance a WHERE a.player_id = p.id) AS appearance_present,
			       EXISTS(SELECT 1 FROM player_skills s WHERE s.player_id = p.id) AS skills_present,
			       EXISTS(SELECT 1 FROM inventory i WHERE i.item_owner = p.id AND i.item_location = 0) AS inventory_present
			FROM players p WHERE
			""" + predicate;
	}

	private Optional<CompanionBodyRecord> loadBody(String query, Object value, String description) {
		try (Connection connection = DatabaseFactory.getConnection()) {
			return loadBody(connection, query, value);
		} catch (SQLException | RuntimeException e) {
			throw new CompanionPersistenceException("Could not load companion body by " + description, e);
		}
	}

	private static Optional<CompanionBodyRecord> loadBody(Connection connection, String query, Object value) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(query)) {
			statement.setObject(1, value);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next())
					return Optional.empty();
				CompanionBodyRecord body = new CompanionBodyRecord(result.getInt("id"), result.getString("name"), result.getInt("account_id"),
					result.getString("account_name"), result.getString("race"), result.getString("gender"), result.getString("player_class"),
					result.getBoolean("online"), result.getBoolean("appearance_present") && result.getBoolean("skills_present")
						&& result.getBoolean("inventory_present"));
				if (result.next())
					throw new SQLException("Multiple companion bodies matched an immutable identity");
				return Optional.of(body);
			}
		}
	}

	private static CompanionCreationClaim loadClaimForUpdate(Connection connection, int ownerPlayerId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(LOAD_CLAIM_FOR_UPDATE_QUERY)) {
			statement.setInt(1, ownerPlayerId);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next())
					throw new SQLException("Companion creation claim is missing");
				return readClaim(result);
			}
		}
	}

	private static CompanionCreationClaim readClaim(ResultSet result) throws SQLException {
		Integer companionId = (Integer) result.getObject("companion_player_id");
		return new CompanionCreationClaim(result.getInt("owner_player_id"), result.getString("body_name"), result.getInt("host_account_id"),
			result.getString("host_account_name"), companionId);
	}

	private static boolean sameClaim(CompanionCreationClaim expected, CompanionCreationClaim actual) {
		return expected.ownerPlayerId() == actual.ownerPlayerId() && expected.databaseName().equals(actual.databaseName())
			&& expected.hostAccountId() == actual.hostAccountId() && expected.hostAccountName().equals(actual.hostAccountName())
			&& (expected.companionPlayerId() == null || expected.companionPlayerId().equals(actual.companionPlayerId()));
	}

	private static Optional<CompanionBinding> loadBindingForUpdate(Connection connection, int ownerPlayerId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(LOAD_BINDING_FOR_UPDATE_QUERY)) {
			statement.setInt(1, ownerPlayerId);
			try (ResultSet result = statement.executeQuery()) {
				return result.next() ? Optional.of(new CompanionBinding(result.getInt("owner_player_id"), result.getInt("companion_player_id"),
					result.getString("role"))) : Optional.empty();
			}
		}
	}

	private static void insertBinding(Connection connection, CompanionBinding binding) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(INSERT_BINDING_QUERY)) {
			statement.setInt(1, binding.ownerPlayerId());
			statement.setInt(2, binding.companionPlayerId());
			statement.setString(3, binding.role());
			statement.executeUpdate();
		}
	}

	private static void upsertGoal(Connection connection, PersistedGoalIntent goalIntent) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(UPSERT_GOAL_QUERY)) {
			statement.setInt(1, goalIntent.ownerPlayerId());
			statement.setString(2, goalIntent.goalType());
			statement.setInt(3, goalIntent.targetId());
			statement.setInt(4, goalIntent.planVersion());
			statement.setString(5, goalIntent.semanticFingerprint());
			statement.executeUpdate();
		}
	}
}
