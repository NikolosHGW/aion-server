package com.aionemu.gameserver.services.ai.creation;

public record CompanionCreationRequest(int ownerPlayerId, String race, String gender) {

	public CompanionCreationRequest {
		if (ownerPlayerId <= 0 || race == null || race.isBlank() || gender == null || gender.isBlank())
			throw new IllegalArgumentException("Invalid companion creation request");
	}
}
