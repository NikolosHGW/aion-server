package com.aionemu.gameserver.services.ai;

public final class CompanionPreflight {

	private CompanionPreflight() {
	}

	public static void requireEnabled(boolean globalEnabled, boolean companionsEnabled) {
		if (!globalEnabled || !companionsEnabled)
			throw new IllegalStateException("Both ai.enabled and ai.companions.enabled must be true");
	}

	public static void validateOwner(boolean connected, boolean inWorld, boolean spawned, boolean serverControlled) {
		if (!connected)
			throw new IllegalStateException("Companion owner must have a live client connection");
		if (!inWorld || !spawned)
			throw new IllegalStateException("Companion owner must be physically present in the world");
		if (serverControlled)
			throw new IllegalStateException("A server-controlled player cannot own a companion");
	}

	public static void validateSpawnSettings(float spawnOffsetDistance, float collisionTolerance) {
		if (!Float.isFinite(spawnOffsetDistance) || spawnOffsetDistance <= 0)
			throw new IllegalArgumentException("Companion spawn offset must be positive and finite");
		if (!Float.isFinite(collisionTolerance) || collisionTolerance <= 0)
			throw new IllegalArgumentException("Companion collision tolerance must be positive and finite");
	}
}
