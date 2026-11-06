package com.aionemu.gameserver.services.ai.creation;

public record CompanionCreationClaim(int ownerPlayerId, String databaseName, int hostAccountId, String hostAccountName,
	Integer companionPlayerId, CompanionBlueprint blueprint) {

	public CompanionCreationClaim(int ownerPlayerId, String databaseName, int hostAccountId, String hostAccountName, Integer companionPlayerId) {
		this(ownerPlayerId, databaseName, hostAccountId, hostAccountName, companionPlayerId, null);
	}

	public CompanionCreationRequest creationRequest(String legacyRace, String legacyGender) {
		return new CompanionCreationRequest(ownerPlayerId, blueprint == null ? legacyRace : blueprint.race(),
			blueprint == null ? legacyGender : blueprint.gender(), blueprint);
	}

	public CompanionCreationClaim {
		if (ownerPlayerId <= 0 || databaseName == null || databaseName.isBlank() || hostAccountId <= 0 || hostAccountName == null
			|| hostAccountName.isBlank() || companionPlayerId != null && companionPlayerId <= 0)
			throw new IllegalArgumentException("Invalid companion creation claim");
	}
}
