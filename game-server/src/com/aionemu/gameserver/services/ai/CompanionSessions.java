package com.aionemu.gameserver.services.ai;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Short index operations never run world/DB code under the directory monitor.
 * Each reservation is also the incarnation token and the lock for that owner's runtime.
 */
final class CompanionSessions<O, S> {

	private final Map<Integer, Lease<O, S>> owners = new HashMap<>();
	private final Map<Integer, Lease<O, S>> bodies = new HashMap<>();
	private long generation;
	private boolean closed;

	synchronized Lease<O, S> reserve(int ownerId, O owner, int capacity) {
		if (closed)
			throw new IllegalStateException("Companion service is shutting down");
		if (capacity <= 0)
			throw new IllegalStateException("Companion capacity must be positive");
		if (owners.containsKey(ownerId))
			throw new IllegalStateException("Your companion is already active or being summoned");
		if (owners.size() >= capacity)
			throw new IllegalStateException("Companion limit reached (" + capacity + "); please try again after a companion is dismissed");
		Lease<O, S> lease = new Lease<>(ownerId, Objects.requireNonNull(owner), ++generation);
		owners.put(ownerId, lease);
		return lease;
	}

	synchronized void claimBody(Lease<O, S> lease, int bodyId) {
		requireUsable(lease);
		if (bodyId <= 0 || lease.bodyId != 0)
			throw new IllegalStateException("Invalid companion body reservation");
		if (bodies.containsKey(bodyId))
			throw new IllegalStateException("This companion body is already active or being summoned");
		lease.bodyId = bodyId;
		bodies.put(bodyId, lease);
	}

	synchronized void publish(Lease<O, S> lease, S session) {
		requireUsable(lease);
		if (lease.bodyId == 0 || lease.session != null)
			throw new IllegalStateException("Companion reservation is not ready for publication");
		lease.session = Objects.requireNonNull(session);
	}

	synchronized Lease<O, S> byOwner(int ownerId, O owner) {
		Lease<O, S> lease = owners.get(ownerId);
		return lease != null && lease.owner == owner ? lease : null;
	}

	synchronized Lease<O, S> byBody(int bodyId) {
		return bodies.get(bodyId);
	}

	/** Only a cancelled incarnation may be recovered by the same persistent owner after relog. */
	synchronized Lease<O, S> pendingRemoval(int ownerId) {
		Lease<O, S> lease = owners.get(ownerId);
		return lease != null && lease.cancelled && lease.session != null ? lease : null;
	}

	synchronized boolean contains(Lease<O, S> lease) {
		return owners.get(lease.ownerId) == lease;
	}

	synchronized boolean usable(Lease<O, S> lease) {
		return contains(lease) && !lease.cancelled && !closed;
	}

	synchronized void requireUsable(Lease<O, S> lease) {
		if (!usable(lease))
			throw new IllegalStateException("Companion session was cancelled; summon again after cleanup");
	}

	synchronized void cancel(Lease<O, S> lease) {
		if (contains(lease))
			lease.cancelled = true;
	}

	synchronized void release(Lease<O, S> lease) {
		if (!contains(lease))
			return;
		lease.cancelled = true;
		owners.remove(lease.ownerId);
		bodies.remove(lease.bodyId, lease);
	}

	synchronized int size() {
		return owners.size();
	}

	synchronized boolean isClosed() {
		return closed;
	}

	synchronized List<Lease<O, S>> close() {
		closed = true;
		owners.values().forEach(lease -> lease.cancelled = true);
		return List.copyOf(owners.values());
	}

	void shutdown(Consumer<Lease<O, S>> cleanup) {
		IllegalStateException failure = null;
		for (Lease<O, S> lease : close()) {
			try {
				cleanup.accept(lease);
			} catch (RuntimeException e) {
				if (failure == null)
					failure = new IllegalStateException("Companion shutdown cleanup incomplete");
				failure.addSuppressed(e);
			}
		}
		if (failure != null)
			throw failure;
	}

	ServerPlayerController guard(Lease<O, S> lease, ServerPlayerController controller, BooleanSupplier active) {
		return new ServerPlayerController() {
			@Override
			public void tick() {
				lease.tryRun(() -> {
					if (usable(lease) && active.getAsBoolean())
						controller.tick();
				});
			}

			@Override
			public void stop() {
				lease.call(() -> { controller.stop(); return null; });
			}
		};
	}

	<T> T withOwner(int ownerId, O owner, T absent, Function<S, T> action) {
		Lease<O, S> lease = byOwner(ownerId, owner);
		if (lease == null)
			return absent;
		return lease.call(() -> usable(lease) && lease.session != null ? action.apply(lease.session) : absent);
	}

	static final class Lease<O, S> {
		private final int ownerId;
		private final O owner;
		private final long generation;
		private final ReentrantLock lock = new ReentrantLock();
		private int bodyId;
		private volatile S session;
		private volatile boolean cancelled;

		private Lease(int ownerId, O owner, long generation) {
			this.ownerId = ownerId;
			this.owner = owner;
			this.generation = generation;
		}

		long generation() {
			return generation;
		}

		S session() {
			return session;
		}

		<T> T call(Supplier<T> action) {
			lock.lock();
			try {
				return action.get();
			} finally {
				lock.unlock();
			}
		}

		// The shared scheduler skips a busy actor instead of waiting behind that owner's DB operation.
		boolean tryRun(Runnable action) {
			if (!lock.tryLock())
				return false;
			try {
				action.run();
				return true;
			} finally {
				lock.unlock();
			}
		}
	}
}
