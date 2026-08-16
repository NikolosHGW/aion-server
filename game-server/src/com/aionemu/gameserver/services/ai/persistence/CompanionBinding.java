package com.aionemu.gameserver.services.ai.persistence;

public record CompanionBinding(int ownerPlayerId, int companionPlayerId, String role) {

	public CompanionBinding {
		if (ownerPlayerId <= 0 || companionPlayerId <= 0 || role == null || role.isBlank())
			throw new IllegalArgumentException("Invalid persistent companion binding");
	}
}
