package com.aionemu.gameserver.services.ai;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.AIConfig;
import com.aionemu.gameserver.utils.ThreadPoolManager;

public final class SyntheticPlayerScheduler {

	private static final Logger log = LoggerFactory.getLogger(SyntheticPlayerScheduler.class);

	private final Map<Integer, ScheduledSyntheticPlayer> scheduledPlayers = new ConcurrentHashMap<>();
	private final IntSupplier maxActions;
	private final LongSupplier budgetNanos;
	private final LongSupplier nanoTime;
	private final boolean scheduleAutomatically;
	private final AtomicLong tickCount = new AtomicLong();
	private final AtomicLong budgetMissCount = new AtomicLong();
	private final AtomicLong lastTickNanos = new AtomicLong();
	private final AtomicLong maxTickNanos = new AtomicLong();
	private final AtomicInteger nextStartIndex = new AtomicInteger();
	private volatile Future<?> future;

	public SyntheticPlayerScheduler() {
		this(() -> AIConfig.SYNTHETIC_SCHEDULER_MAX_ACTIONS, () -> AIConfig.SYNTHETIC_SCHEDULER_BUDGET_MS * 1_000_000L, System::nanoTime, true);
	}

	SyntheticPlayerScheduler(IntSupplier maxActions, LongSupplier budgetNanos, LongSupplier nanoTime, boolean scheduleAutomatically) {
		this.maxActions = maxActions;
		this.budgetNanos = budgetNanos;
		this.nanoTime = nanoTime;
		this.scheduleAutomatically = scheduleAutomatically;
	}

	public synchronized void register(ServerControlledPlayer player) {
		registerScheduled(player);
	}

	void registerScheduled(ScheduledSyntheticPlayer player) {
		ScheduledSyntheticPlayer existing = scheduledPlayers.putIfAbsent(player.getObjectId(), player);
		if (existing != null && existing != player)
			throw new IllegalStateException("Scheduler object ID collision: " + player.getObjectId());
		if (scheduleAutomatically)
			ensureStarted();
	}

	public synchronized void unregister(ServerControlledPlayer player) {
		scheduledPlayers.remove(player.getObjectId(), player);
		stopIfEmpty();
	}

	public boolean contains(ServerControlledPlayer player) {
		return scheduledPlayers.get(player.getObjectId()) == player;
	}

	public boolean contains(int objectId) {
		return scheduledPlayers.containsKey(objectId);
	}

	TickStats runOnce() {
		long started = nanoTime.getAsLong();
		tickCount.incrementAndGet();
		List<ScheduledSyntheticPlayer> players = List.copyOf(scheduledPlayers.values());
		if (players.isEmpty()) {
			recordDuration(started);
			return new TickStats(0, false);
		}

		int actionLimit = Math.max(1, maxActions.getAsInt());
		long budget = Math.max(1, budgetNanos.getAsLong());
		long deadline = started + budget;
		int actions = 0;
		boolean budgetMiss = false;
		int startIndex = Math.floorMod(nextStartIndex.getAndIncrement(), players.size());
		for (int offset = 0; offset < players.size(); offset++) {
			if (actions >= actionLimit)
				break;
			if (nanoTime.getAsLong() >= deadline) {
				budgetMiss = true;
				break;
			}
			ScheduledSyntheticPlayer player = players.get((startIndex + offset) % players.size());
			if (!player.isFeatureEnabled()) {
				requestRemoval(player, "feature-disabled");
				continue;
			}
			if (!player.isActive())
				continue;
			try {
				player.tick();
				actions++;
			} catch (RuntimeException e) {
				log.error("Synthetic player {} tick failed; requesting removal", player.getObjectId(), e);
				requestRemoval(player, "scheduler-error");
			}
		}
		// A synchronous native action cannot be preempted. Count its overrun even when
		// it was the last action (or exhausted the action limit) in this pass.
		budgetMiss |= recordDuration(started) > budget;
		if (budgetMiss) {
			budgetMissCount.incrementAndGet();
		}
		return new TickStats(actions, budgetMiss);
	}

	private long recordDuration(long started) {
		long elapsed = Math.max(0, nanoTime.getAsLong() - started);
		lastTickNanos.set(elapsed);
		maxTickNanos.accumulateAndGet(elapsed, Math::max);
		return elapsed;
	}

	public long getLastTickNanos() { return lastTickNanos.get(); }
	public long getMaxTickNanos() { return maxTickNanos.get(); }

	private void requestRemoval(ScheduledSyntheticPlayer player, String reason) {
		try {
			player.requestRemoval(reason);
		} catch (RuntimeException e) {
			// One failed cleanup must not cancel the shared scheduled future for every actor.
			log.error("Synthetic player {} cleanup failed; other actors will continue", player.getObjectId(), e);
		}
	}

	public long getTickCount() {
		return tickCount.get();
	}

	public long getBudgetMissCount() {
		return budgetMissCount.get();
	}

	public synchronized void shutdown() {
		if (future != null) {
			future.cancel(false);
			future = null;
		}
		scheduledPlayers.clear();
	}

	private synchronized void ensureStarted() {
		if (future == null || future.isDone()) {
			int period = Math.max(50, AIConfig.SYNTHETIC_SCHEDULER_PERIOD_MS);
			future = ThreadPoolManager.getInstance().scheduleAtFixedRate(this::runOnce, period, period);
			log.info("Synthetic player scheduler started with period {} ms", period);
		}
	}

	private synchronized void stopIfEmpty() {
		if (scheduledPlayers.isEmpty() && future != null) {
			future.cancel(false);
			future = null;
			log.info("Synthetic player scheduler stopped");
		}
	}

	record TickStats(int actions, boolean budgetMiss) {
	}
}
