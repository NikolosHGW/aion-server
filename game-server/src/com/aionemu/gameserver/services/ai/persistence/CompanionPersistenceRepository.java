package com.aionemu.gameserver.services.ai.persistence;

import java.util.Optional;

public interface CompanionPersistenceRepository {

	Optional<CompanionPersistentState> load(int ownerPlayerId);

	void saveChosenGoal(CompanionBinding binding, PersistedGoalIntent goalIntent);

	boolean deleteGoal(int ownerPlayerId, int companionPlayerId);
}
