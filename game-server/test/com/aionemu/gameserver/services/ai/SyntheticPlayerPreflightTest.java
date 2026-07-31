package com.aionemu.gameserver.services.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SyntheticPlayerPreflightTest {

	@Test
	void flagsDisabled_rejectsSpawnWithoutWorldMutation() {
		assertThrows(IllegalStateException.class, () -> SyntheticPlayerPreflight.requireEnabled(false, true));
		assertThrows(IllegalStateException.class, () -> SyntheticPlayerPreflight.requireEnabled(true, false));
		assertDoesNotThrow(() -> SyntheticPlayerPreflight.requireEnabled(true, true));
	}

	@Test
	void objectIdCollision_rejectsSpawnWithoutWorldMutation() {
		TestOccupancy occupancy = new TestOccupancy();
		Object realPlayer = new Object();
		occupancy.worldById.put(42, realPlayer);

		assertThrows(IllegalStateException.class, () -> SyntheticPlayerPreflight.validate(42, "[AI]Template", 16, occupancy));
		assertSame(realPlayer, occupancy.worldById.get(42));
		assertEquals(0, occupancy.mutations);
	}

	@Test
	void runtimeNameCollision_rejectsSpawnWithoutWorldMutation() {
		TestOccupancy occupancy = new TestOccupancy();
		Object realPlayer = new Object();
		occupancy.playersByName.put("[AI]Template", realPlayer);

		assertThrows(IllegalStateException.class, () -> SyntheticPlayerPreflight.validate(42, "[AI]Template", 16, occupancy));
		assertSame(realPlayer, occupancy.playersByName.get("[AI]Template"));
		assertEquals(0, occupancy.mutations);
	}

	@Test
	void secondSpawn_isRejectedAndDoesNotCreateDuplicate() {
		TestOccupancy occupancy = new TestOccupancy();
		occupancy.registryIds.put(42, new Object());

		assertThrows(IllegalStateException.class, () -> SyntheticPlayerPreflight.validate(42, "[AI]Template", 16, occupancy));
		assertEquals(1, occupancy.registryIds.size());
		assertEquals(0, occupancy.mutations);
	}

	@Test
	void finalNameIsValidatedBeforeWorldLookup() {
		TestOccupancy occupancy = new TestOccupancy();
		occupancy.failOnLookup = true;

		assertThrows(IllegalArgumentException.class, () -> SyntheticPlayerPreflight.validate(42, "[AI]NameThatIsTooLong", 16, occupancy));
		assertEquals(0, occupancy.lookups);
	}

	private static final class TestOccupancy implements SyntheticPlayerPreflight.Occupancy {

		private final Map<Integer, Object> worldById = new HashMap<>();
		private final Map<Integer, Object> playersById = new HashMap<>();
		private final Map<String, Object> playersByName = new HashMap<>();
		private final Map<Integer, Object> registryIds = new HashMap<>();
		private final Map<Integer, Object> schedulerIds = new HashMap<>();
		private final Map<Integer, Object> movementIds = new HashMap<>();
		private int lookups;
		private int mutations;
		private boolean failOnLookup;

		@Override
		public Object worldObjectById(int objectId) {
			lookup();
			return worldById.get(objectId);
		}

		@Override
		public Object playerById(int objectId) {
			lookup();
			return playersById.get(objectId);
		}

		@Override
		public Object playerByName(String name) {
			lookup();
			return playersByName.get(name);
		}

		@Override
		public boolean registryContains(int objectId) {
			lookup();
			return registryIds.containsKey(objectId);
		}

		@Override
		public boolean schedulerContains(int objectId) {
			lookup();
			return schedulerIds.containsKey(objectId);
		}

		@Override
		public boolean movementContains(int objectId) {
			lookup();
			return movementIds.containsKey(objectId);
		}

		private void lookup() {
			if (failOnLookup)
				fail("Availability lookup happened before name validation");
			lookups++;
		}
	}
}
