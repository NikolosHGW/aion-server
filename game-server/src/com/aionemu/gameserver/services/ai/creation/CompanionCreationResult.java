package com.aionemu.gameserver.services.ai.creation;

public record CompanionCreationResult(Status status, CompanionBodyRecord body) {

	public CompanionCreationResult {
		if (status == null || body == null)
			throw new IllegalArgumentException("Companion creation result must be complete");
	}

	public enum Status {
		CREATED,
		RECOVERED,
		ALREADY_EXISTS
	}
}
