package com.aionemu.gameserver.services.ai.creation;

public record CompanionCreationRequest(int ownerPlayerId, String race, String gender, CompanionBlueprint blueprint) {

	public CompanionCreationRequest(int ownerPlayerId, String race, String gender) {
		this(ownerPlayerId, race, gender, null);
	}

	public CompanionCreationRequest {
		if (ownerPlayerId <= 0 || race == null || race.isBlank() || gender == null || gender.isBlank())
			throw new IllegalArgumentException("Invalid companion creation request");
		if (blueprint != null && (!race.equals(blueprint.race()) || !gender.equals(blueprint.gender())))
			throw new IllegalArgumentException("Blueprint identity differs from creation request");
	}
}
