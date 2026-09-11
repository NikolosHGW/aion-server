package com.aionemu.gameserver.services.ai.creation;

public record CompanionBodyRecord(int playerId, String databaseName, int accountId, String accountName, String race, String gender,
	String playerClass, boolean online, boolean standardCreationComplete) {

	public CompanionBodyRecord {
		if (playerId <= 0 || databaseName == null || databaseName.isBlank() || accountId <= 0 || accountName == null || accountName.isBlank()
			|| race == null || gender == null || playerClass == null)
			throw new IllegalArgumentException("Invalid companion body record");
	}
}
