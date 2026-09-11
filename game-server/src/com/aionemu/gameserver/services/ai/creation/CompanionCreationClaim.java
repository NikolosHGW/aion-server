package com.aionemu.gameserver.services.ai.creation;

public record CompanionCreationClaim(int ownerPlayerId, String databaseName, int hostAccountId, String hostAccountName,
	Integer companionPlayerId) {

	public CompanionCreationClaim {
		if (ownerPlayerId <= 0 || databaseName == null || databaseName.isBlank() || hostAccountId <= 0 || hostAccountName == null
			|| hostAccountName.isBlank() || companionPlayerId != null && companionPlayerId <= 0)
			throw new IllegalArgumentException("Invalid companion creation claim");
	}
}
