package com.aionemu.gameserver.services.ai.persistence;

import java.util.Optional;

public record CompanionPersistentState(CompanionBinding binding, Optional<PersistedGoalIntent> goalIntent) {

	public CompanionPersistentState {
		if (binding == null || goalIntent == null)
			throw new IllegalArgumentException("Persistent companion state must be complete");
		if (goalIntent.isPresent() && goalIntent.get().ownerPlayerId() != binding.ownerPlayerId())
			throw new IllegalArgumentException("Binding and goal owner must match");
	}
}
