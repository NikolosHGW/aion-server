package com.aionemu.gameserver.services.ai.persistence;

import java.util.Optional;

import com.aionemu.gameserver.services.ai.creation.CompanionBodyRecord;
import com.aionemu.gameserver.services.ai.creation.CompanionCreationClaim;

public interface CompanionPersistenceRepository {

	Optional<CompanionPersistentState> load(int ownerPlayerId);

	Optional<CompanionCreationClaim> loadCreationClaim(int ownerPlayerId);

	Optional<CompanionBodyRecord> findCompanionBodyByName(String databaseName);

	Optional<CompanionBodyRecord> loadCompanionBody(int companionPlayerId);

	void reserveCreation(CompanionCreationClaim claim);

	CompanionBinding finalizeCreation(CompanionCreationClaim claim, CompanionBodyRecord body);

	void saveChosenGoal(CompanionBinding binding, PersistedGoalIntent goalIntent);

	void saveGoalForExistingBinding(CompanionBinding binding, PersistedGoalIntent goalIntent);

	boolean deleteGoal(int ownerPlayerId, int companionPlayerId);
}
