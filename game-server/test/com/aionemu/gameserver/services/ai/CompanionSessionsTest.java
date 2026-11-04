package com.aionemu.gameserver.services.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class CompanionSessionsTest {

	private final CompanionSessions<Object, AtomicInteger> sessions = new CompanionSessions<>();
	private final Object ownerA = new Object();
	private final Object ownerB = new Object();

	@Test
	void twoOwnersHaveIndependentCommandsAndStatusAndCannotUseAnotherRuntimeIdentity() {
		var first = sessions.reserve(1, ownerA, 2);
		var second = sessions.reserve(2, ownerB, 2);
		sessions.claimBody(first, 101);
		sessions.claimBody(second, 102);
		sessions.publish(first, new AtomicInteger());
		sessions.publish(second, new AtomicInteger());

		assertEquals(1, sessions.withOwner(1, ownerA, -1, AtomicInteger::incrementAndGet));
		assertEquals(-1, sessions.withOwner(1, ownerB, -1, AtomicInteger::incrementAndGet));
		assertEquals(-1, sessions.withOwner(1, new Object(), -1, AtomicInteger::incrementAndGet));
		assertEquals(0, sessions.withOwner(2, ownerB, -1, AtomicInteger::get));
		sessions.release(first);
		assertSame(second, sessions.byBody(102));
		assertEquals(1, sessions.withOwner(2, ownerB, -1, AtomicInteger::incrementAndGet));
	}

	@Test
	void pendingSummonsCountTowardCapacityAndDuplicateOwnerOrBodyDoesNotOverwriteAnything() {
		var first = sessions.reserve(1, ownerA, 2);
		assertThrows(IllegalStateException.class, () -> sessions.reserve(1, new Object(), 2));
		var second = sessions.reserve(2, ownerB, 2);
		assertThrows(IllegalStateException.class, () -> sessions.reserve(3, new Object(), 2));
		sessions.claimBody(first, 101);
		assertThrows(IllegalStateException.class, () -> sessions.claimBody(second, 101));
		sessions.release(second); // failed load/claim rolls back only its own reservation
		assertSame(first, sessions.byBody(101));
		assertSame(first, sessions.byOwner(1, ownerA));
		assertDoesNotThrow(() -> sessions.reserve(3, new Object(), 2));
	}

	@Test
	void simultaneousReservationsCannotExceedCapacity() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<Boolean>> results = new ArrayList<>();
			for (int id = 1; id <= 40; id++) {
				int ownerId = id;
				results.add(workers.submit(() -> {
					assertTrue(start.await(5, TimeUnit.SECONDS));
					try {
						sessions.reserve(ownerId, new Object(), 3);
						return true;
					} catch (IllegalStateException expected) {
						return false;
					}
				}));
			}
			start.countDown();
			int admitted = 0;
			for (var result : results)
				if (result.get(5, TimeUnit.SECONDS))
					admitted++;
			assertEquals(3, admitted);
			assertEquals(3, sessions.size());
		}
	}

	@Test
	void oldGenerationCannotCancelRemoveOrPublishOverNewIncarnationOfSameOwnerAndBody() {
		var old = sessions.reserve(1, ownerA, 1);
		sessions.claimBody(old, 101);
		sessions.publish(old, new AtomicInteger());
		sessions.release(old);
		var current = sessions.reserve(1, ownerA, 1);
		sessions.claimBody(current, 101);
		sessions.publish(current, new AtomicInteger(42));

		sessions.cancel(old);
		sessions.release(old);
		assertThrows(IllegalStateException.class, () -> sessions.publish(old, new AtomicInteger()));
		assertTrue(current.generation() > old.generation());
		assertTrue(sessions.usable(current));
		assertSame(current, sessions.byBody(101));
		assertEquals(42, sessions.withOwner(1, ownerA, -1, AtomicInteger::get));
	}

	@Test
	void ownerLeavingDuringLoadPreventsPublicationWithoutFreeingTheBodyPrematurely() throws Exception {
		var lease = sessions.reserve(1, ownerA, 1);
		sessions.claimBody(lease, 101);
		CountDownLatch loading = new CountDownLatch(1);
		CountDownLatch proceed = new CountDownLatch(1);
		try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
			var loader = workers.submit(() -> lease.call(() -> {
				loading.countDown();
				await(proceed);
				assertThrows(IllegalStateException.class, () -> sessions.publish(lease, new AtomicInteger()));
				sessions.release(lease);
				return null;
			}));
			assertTrue(loading.await(5, TimeUnit.SECONDS));
			try {
				sessions.cancel(lease);
				assertSame(lease, sessions.byBody(101));
				assertThrows(IllegalStateException.class, () -> sessions.reserve(1, ownerA, 1));
			} finally {
				proceed.countDown();
			}
			loader.get(5, TimeUnit.SECONDS);
			assertEquals(0, sessions.size());
		}
	}

	@Test
	void shutdownClosesAdmissionCancelsAllPendingLoadsAndAttemptsEveryCleanupDespiteFailure() {
		var first = sessions.reserve(1, ownerA, 3);
		var second = sessions.reserve(2, ownerB, 3);
		var visited = new HashSet<>();
		var failure = assertThrows(IllegalStateException.class, () -> sessions.shutdown(lease -> {
			visited.add(lease);
			assertFalse(sessions.usable(first));
			assertFalse(sessions.usable(second));
			assertThrows(IllegalStateException.class, () -> sessions.reserve(3, new Object(), 3));
			if (lease == first)
				throw new IllegalStateException("world cleanup failed");
			sessions.release(lease);
		}));
		assertEquals(2, visited.size());
		assertEquals(1, failure.getSuppressed().length);
		assertTrue(sessions.contains(first)); // retain capacity/identity for cleanup retry
		assertFalse(sessions.contains(second));
		sessions.shutdown(sessions::release);
		sessions.shutdown(_ -> fail("already removed"));
		assertEquals(0, sessions.size());
	}

	@Test
	void staleSchedulerSnapshotAndCancelledSessionCannotTick() {
		var lease = sessions.reserve(1, ownerA, 1);
		var ticks = new AtomicInteger();
		var controller = sessions.guard(lease, counter(ticks), () -> true);
		controller.tick();
		assertEquals(1, ticks.get());
		sessions.cancel(lease);
		controller.tick();
		sessions.release(lease);
		sessions.reserve(1, ownerA, 1);
		controller.tick();
		assertEquals(1, ticks.get());
	}

	@Test
	void busyOwnerDoesNotBlockAnotherActorOrSharedScheduler() throws Exception {
		var first = sessions.reserve(1, ownerA, 2);
		var second = sessions.reserve(2, ownerB, 2);
		var ticksA = new AtomicInteger();
		var ticksB = new AtomicInteger();
		var actorA = sessions.guard(first, counter(ticksA), () -> true);
		var actorB = sessions.guard(second, counter(ticksB), () -> true);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
			var command = workers.submit(() -> first.call(() -> {
				entered.countDown();
				await(release);
				return null;
			}));
			assertTrue(entered.await(5, TimeUnit.SECONDS));
			try {
				var schedulerPass = workers.submit(() -> { actorA.tick(); actorB.tick(); });
				schedulerPass.get(2, TimeUnit.SECONDS);
				assertEquals(0, ticksA.get());
				assertEquals(1, ticksB.get());
			} finally {
				release.countDown();
			}
			command.get(5, TimeUnit.SECONDS);
			actorA.tick();
			assertEquals(1, ticksA.get());
		}
	}

	@Test
	void invalidCapacityDoesNotReserveAnOwner() {
		assertThrows(IllegalStateException.class, () -> sessions.reserve(1, ownerA, 0));
		assertThrows(IllegalStateException.class, () -> sessions.reserve(1, ownerA, -1));
		assertEquals(0, sessions.size());
	}

	private static ServerPlayerController counter(AtomicInteger ticks) {
		return new ServerPlayerController() {
			public void tick() { ticks.incrementAndGet(); }
			public void stop() {}
		};
	}

	private static void await(CountDownLatch latch) {
		try {
			assertTrue(latch.await(5, TimeUnit.SECONDS));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}
}
