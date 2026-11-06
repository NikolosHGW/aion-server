package com.aionemu.gameserver.services.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class SyntheticPlayerSchedulerTest {

	@Test void lastActionOverrunIsCountedEvenAtActionLimit() {
		AtomicLong clock = new AtomicLong();
		var scheduler = new SyntheticPlayerScheduler(() -> 1, () -> 100, clock::get, false);
		scheduler.registerScheduled(new ScheduledSyntheticPlayer() {
			public int getObjectId() { return 1; }
			public boolean isActive() { return true; }
			public boolean isFeatureEnabled() { return true; }
			public void tick() { clock.addAndGet(101); }
			public void requestRemoval(String reason) { }
		});
		assertTrue(scheduler.runOnce().budgetMiss());
		assertEquals(101, scheduler.getLastTickNanos());
		assertEquals(101, scheduler.getMaxTickNanos());
		assertEquals(1, scheduler.getBudgetMissCount());
	}

	@Test void exactBudgetIsNotAnOverrunAndEmptyPassResetsLastDuration() {
		AtomicLong clock = new AtomicLong();
		var scheduler = new SyntheticPlayerScheduler(() -> 1, () -> 100, clock::get, false);
		scheduler.registerScheduled(new ScheduledSyntheticPlayer() {
			public int getObjectId() { return 1; }
			public boolean isActive() { return true; }
			public boolean isFeatureEnabled() { return true; }
			public void tick() { clock.addAndGet(100); }
			public void requestRemoval(String reason) { }
		});
		assertFalse(scheduler.runOnce().budgetMiss());
		scheduler.shutdown(); assertEquals(0, scheduler.runOnce().actions());
		assertEquals(0, scheduler.getLastTickNanos()); assertEquals(100, scheduler.getMaxTickNanos());
	}

	@Test
	void schedulerRespectsActionAndTimeBudget() {
		AtomicLong clock = new AtomicLong();
		SyntheticPlayerScheduler scheduler = new SyntheticPlayerScheduler(() -> 10, () -> 15, () -> clock.addAndGet(10), false);
		TestPlayer first = new TestPlayer(1);
		TestPlayer second = new TestPlayer(2);
		scheduler.registerScheduled(first);
		scheduler.registerScheduled(second);

		SyntheticPlayerScheduler.TickStats stats = scheduler.runOnce();

		assertEquals(1, stats.actions());
		assertTrue(stats.budgetMiss());
		assertEquals(1, first.ticks + second.ticks);
		assertEquals(1, scheduler.getBudgetMissCount());
	}

	@Test
	void schedulerRespectsMaximumActions() {
		SyntheticPlayerScheduler scheduler = new SyntheticPlayerScheduler(() -> 1, () -> Long.MAX_VALUE / 2, System::nanoTime, false);
		TestPlayer first = new TestPlayer(1);
		TestPlayer second = new TestPlayer(2);
		scheduler.registerScheduled(first);
		scheduler.registerScheduled(second);

		assertEquals(1, scheduler.runOnce().actions());
		assertEquals(1, first.ticks + second.ticks);
	}

	@Test
	void inactivePlayerDoesNotTick() {
		SyntheticPlayerScheduler scheduler = new SyntheticPlayerScheduler(() -> 1, () -> 1_000_000, System::nanoTime, false);
		TestPlayer player = new TestPlayer(1);
		player.active = false;
		scheduler.registerScheduled(player);

		assertEquals(0, scheduler.runOnce().actions());
		assertEquals(0, player.ticks);
	}

	@Test
	void disabledAtRuntimeRequestsRemoval() {
		SyntheticPlayerScheduler scheduler = new SyntheticPlayerScheduler(() -> 1, () -> 1_000_000, System::nanoTime, false);
		TestPlayer player = new TestPlayer(1);
		player.featureEnabled = false;
		scheduler.registerScheduled(player);

		assertEquals(0, scheduler.runOnce().actions());
		assertEquals(1, player.removals.get());
		assertEquals("feature-disabled", player.lastRemovalReason);
	}

	@Test
	void actionLimitUsesRoundRobinAcrossPlayers() {
		SyntheticPlayerScheduler scheduler = new SyntheticPlayerScheduler(() -> 1, () -> Long.MAX_VALUE / 2, System::nanoTime, false);
		TestPlayer first = new TestPlayer(1);
		TestPlayer second = new TestPlayer(2);
		scheduler.registerScheduled(first);
		scheduler.registerScheduled(second);

		scheduler.runOnce();
		scheduler.runOnce();

		assertEquals(1, first.ticks);
		assertEquals(1, second.ticks);
	}

	private static final class TestPlayer implements ScheduledSyntheticPlayer {

		private final int objectId;
		private final AtomicInteger removals = new AtomicInteger();
		private boolean active = true;
		private boolean featureEnabled = true;
		private int ticks;
		private String lastRemovalReason;
		private boolean failRemoval;
		private boolean failTick;

		private TestPlayer(int objectId) {
			this.objectId = objectId;
		}

		@Override
		public int getObjectId() {
			return objectId;
		}

		@Override
		public boolean isActive() {
			return active;
		}

		@Override
		public boolean isFeatureEnabled() {
			return featureEnabled;
		}

		@Override
		public void tick() {
			if (failTick)
				throw new IllegalStateException("tick failure");
			ticks++;
		}

		@Override
		public void requestRemoval(String reason) {
			lastRemovalReason = reason;
			removals.incrementAndGet();
			if (failRemoval)
				throw new IllegalStateException("cleanup failure");
		}
	}

	@Test
	void failingCleanupOfDisabledActorDoesNotStopOtherActorsOrFuturePasses() {
		var scheduler = new SyntheticPlayerScheduler(() -> 10, () -> Long.MAX_VALUE / 2, System::nanoTime, false);
		var broken = new TestPlayer(1);
		broken.featureEnabled = false;
		broken.failRemoval = true;
		var healthy = new TestPlayer(2);
		scheduler.registerScheduled(broken);
		scheduler.registerScheduled(healthy);
		assertDoesNotThrow(scheduler::runOnce);
		assertDoesNotThrow(scheduler::runOnce);
		assertEquals(2, healthy.ticks);
		assertEquals(2, broken.removals.get());
	}

	@Test
	void failingTickAndItsCleanupDoNotStopOtherActors() {
		var scheduler = new SyntheticPlayerScheduler(() -> 10, () -> Long.MAX_VALUE / 2, System::nanoTime, false);
		var broken = new TestPlayer(1);
		broken.failTick = true;
		broken.failRemoval = true;
		var healthy = new TestPlayer(2);
		scheduler.registerScheduled(broken);
		scheduler.registerScheduled(healthy);
		assertDoesNotThrow(scheduler::runOnce);
		assertEquals(1, healthy.ticks);
		assertEquals("scheduler-error", broken.lastRemovalReason);
	}

	@Test
	void tenActorsWithSingleActionBudgetAllReceiveTurns() {
		var scheduler = new SyntheticPlayerScheduler(() -> 1, () -> Long.MAX_VALUE / 2, System::nanoTime, false);
		var actors = new TestPlayer[10];
		for (int i = 0; i < actors.length; i++) {
			actors[i] = new TestPlayer(i + 1);
			scheduler.registerScheduled(actors[i]);
		}
		for (int i = 0; i < 30; i++)
			scheduler.runOnce();
		for (var actor : actors)
			assertEquals(3, actor.ticks);
	}
}
