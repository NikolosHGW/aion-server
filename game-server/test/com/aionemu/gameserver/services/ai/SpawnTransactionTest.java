package com.aionemu.gameserver.services.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SpawnTransactionTest {

	@Test
	void partialSpawnFailureRollsBackRegistryAndWorld() {
		for (FailurePoint point : FailurePoint.values()) {
			if (point == FailurePoint.NONE)
				continue;
			TestSteps steps = new TestSteps(point);

			assertThrows(IllegalStateException.class, () -> SpawnTransaction.execute(steps), point.name());

			assertFalse(steps.wrapperRegistered, point.name());
			assertFalse(steps.runtimeStored, point.name());
			assertFalse(steps.runtimeSpawned, point.name());
			assertFalse(steps.schedulerRegistered, point.name());
			assertFalse(steps.movementRegistered, point.name());
			assertTrue(steps.rollbackVerified, point.name());
		}
	}

	@Test
	void cleanupAfterPartialFailureDoesNotDeleteAnotherRealPlayer() {
		Object realPlayer = new Object();
		TestSteps steps = new TestSteps(FailurePoint.SPAWN);
		steps.world.put(7, realPlayer);

		assertThrows(IllegalStateException.class, () -> SpawnTransaction.execute(steps));

		assertSame(realPlayer, steps.world.get(7));
		assertTrue(steps.rollbackVerified);
	}

	@Test
	void despawnCleanupIsIdempotent() {
		TestSteps steps = new TestSteps(FailurePoint.NONE);
		SpawnTransaction.execute(steps);

		steps.rollback();
		steps.rollback();
		steps.verifyRolledBack();

		assertFalse(steps.wrapperRegistered);
		assertFalse(steps.runtimeStored);
		assertFalse(steps.runtimeSpawned);
		assertFalse(steps.schedulerRegistered);
		assertFalse(steps.movementRegistered);
	}

	private enum FailurePoint {
		REGISTER,
		STORE,
		SPAWN,
		SCHEDULER,
		NONE
	}

	private static final class TestSteps implements SpawnTransaction.Steps {

		private final Object runtimePlayer = new Object();
		private final FailurePoint failurePoint;
		private final Map<Integer, Object> world = new HashMap<>();
		private boolean wrapperRegistered;
		private boolean runtimeStored;
		private boolean runtimeSpawned;
		private boolean schedulerRegistered;
		private boolean movementRegistered;
		private boolean rollbackVerified;

		private TestSteps(FailurePoint failurePoint) {
			this.failurePoint = failurePoint;
		}

		@Override
		public void registerWrapper() {
			failAt(FailurePoint.REGISTER);
			wrapperRegistered = true;
		}

		@Override
		public void storeWorldObject() {
			failAt(FailurePoint.STORE);
			runtimeStored = true;
			world.put(42, runtimePlayer);
		}

		@Override
		public void spawnWorldObject() {
			failAt(FailurePoint.SPAWN);
			runtimeSpawned = true;
			movementRegistered = true;
		}

		@Override
		public void registerScheduler() {
			failAt(FailurePoint.SCHEDULER);
			schedulerRegistered = true;
		}

		@Override
		public void rollback() {
			schedulerRegistered = false;
			movementRegistered = false;
			runtimeSpawned = false;
			world.remove(42, runtimePlayer);
			runtimeStored = false;
			wrapperRegistered = false;
		}

		@Override
		public void verifyRolledBack() {
			assertFalse(wrapperRegistered);
			assertFalse(runtimeStored);
			assertFalse(runtimeSpawned);
			assertFalse(schedulerRegistered);
			assertFalse(movementRegistered);
			assertNotSame(runtimePlayer, world.get(42));
			rollbackVerified = true;
		}

		private void failAt(FailurePoint point) {
			if (failurePoint == point)
				throw new IllegalStateException("Injected failure at " + point);
		}
	}
}
