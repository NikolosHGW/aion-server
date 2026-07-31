package com.aionemu.gameserver.services.ai;

public final class SyntheticPlayerPreflight {

	private SyntheticPlayerPreflight() {
	}

	public static void requireEnabled(boolean globalEnabled, boolean syntheticPlayersEnabled) {
		if (!globalEnabled || !syntheticPlayersEnabled)
			throw new IllegalStateException("Both ai.enabled and ai.synthetic_players.enabled must be true");
	}

	public static void validate(int objectId, String runtimeName, int maxLength, Occupancy occupancy) {
		validateRuntimeName(runtimeName, maxLength);
		validateAvailable(objectId, runtimeName, occupancy);
	}

	public static void validateRuntimeName(String runtimeName, int maxLength) {
		if (runtimeName == null || runtimeName.isBlank())
			throw new IllegalArgumentException("Synthetic player runtime name is empty");
		if (maxLength <= 0 || runtimeName.length() > maxLength)
			throw new IllegalArgumentException(
				"Synthetic player runtime name length " + runtimeName.length() + " exceeds configured maximum " + maxLength);
	}

	public static void validateAvailable(int objectId, String runtimeName, Occupancy occupancy) {
		if (objectId <= 0)
			throw new IllegalArgumentException("Template character object ID must be positive");
		if (occupancy.worldObjectById(objectId) != null)
			throw new IllegalStateException("Object ID " + objectId + " is already present in World");
		if (occupancy.playerById(objectId) != null)
			throw new IllegalStateException("Object ID " + objectId + " is already present in PlayerContainer");
		if (occupancy.playerByName(runtimeName) != null)
			throw new IllegalStateException("Runtime name " + runtimeName + " is already present in PlayerContainer");
		if (occupancy.registryContains(objectId))
			throw new IllegalStateException("Object ID " + objectId + " is already present in synthetic player registry");
		if (occupancy.schedulerContains(objectId))
			throw new IllegalStateException("Object ID " + objectId + " is already present in synthetic player scheduler");
		if (occupancy.movementContains(objectId))
			throw new IllegalStateException("Object ID " + objectId + " is already present in movement manager");
	}

	public interface Occupancy {

		Object worldObjectById(int objectId);

		Object playerById(int objectId);

		Object playerByName(String name);

		boolean registryContains(int objectId);

		boolean schedulerContains(int objectId);

		boolean movementContains(int objectId);
	}
}
