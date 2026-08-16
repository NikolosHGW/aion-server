package com.aionemu.gameserver.services.ai.persistence;

public record PersistedGoalIntent(int ownerPlayerId, String goalType, int targetId, int planVersion, String semanticFingerprint) {

	public PersistedGoalIntent {
		if (ownerPlayerId <= 0 || goalType == null || goalType.isBlank() || targetId <= 0 || planVersion <= 0
			|| semanticFingerprint == null || !semanticFingerprint.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException("Invalid persisted companion goal intent");
	}
}
