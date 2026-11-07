package com.aionemu.gameserver.services.ai.memory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.AIConfig;
import com.aionemu.gameserver.dao.CompanionMemoryDAO;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.questEngine.model.*;
import com.aionemu.gameserver.services.ai.CompanionService;
import com.aionemu.gameserver.utils.PositionUtil;
import static com.aionemu.gameserver.model.DialogAction.SELECTED_QUEST_NOREWARD;

/** One bounded, independent persistence worker. No SQL or mutable actor references in capture.
 * Configuration is startup-only. close() is the shutdown/disable commit barrier, not a hot-reload API. */
public final class CompanionEpisodeService implements AutoCloseable {
	private static final Logger log = LoggerFactory.getLogger(CompanionEpisodeService.class);
	private final CompanionMemoryStore store;
	private final BooleanSupplier enabled;
	private final LongSupplier clock;
	private final String realm;
	private final ArrayBlockingQueue<Queued> queue;
	private final AtomicBoolean closed = new AtomicBoolean();
	private final ReentrantLock worker = new ReentrantLock();
	private final AtomicLong dropped = new AtomicLong(), failures = new AtomicLong(), conflicts = new AtomicLong(), persisted = new AtomicLong();
	private ScheduledExecutorService executor;
	private long retryAfter;
	private record Queued(QuestCompletionEvidence evidence, int attempts) { }

	CompanionEpisodeService(CompanionMemoryStore store, BooleanSupplier enabled, String realm, LongSupplier clock, int capacity) {
		this.store = store; this.enabled = enabled; this.realm = realm; this.clock = clock;
		if (capacity < 1 || capacity > 4096) throw new IllegalArgumentException("W0 queue capacity");
		queue = new ArrayBlockingQueue<>(capacity);
	}
	public static CompanionEpisodeService getInstance() { return Holder.INSTANCE; }
	private static final class Holder {
		static final CompanionEpisodeService INSTANCE = new CompanionEpisodeService(new CompanionMemoryDAO(),
			CompanionEpisodeService::flagsEnabled, AIConfig.WORLD_MEMORY_REALM, System::currentTimeMillis, 128);
	}
	public static boolean flagsEnabled() {
		return AIConfig.ENABLED && AIConfig.SYNTHETIC_PLAYERS_ENABLED && AIConfig.COMPANIONS_ENABLED
			&& AIConfig.COMPANION_CREATION_ENABLED && AIConfig.COMPANION_PROFILE_ENABLED
			&& AIConfig.WORLD_EVENTS_ENABLED && AIConfig.WORLD_MEMORY_ENABLED;
	}
	public synchronized void start() {
		if (executor != null || closed.get() || !enabled.getAsBoolean()) return;
		if (!QuestCompletionEvidence.validRealm(realm)) { log.error("W0 disabled: configure a stable ai.world_memory.realm"); return; }
		executor = Executors.newSingleThreadScheduledExecutor(task -> {
			var thread = new Thread(task, "CompanionMemory"); thread.setDaemon(true); return thread;
		});
		executor.scheduleWithFixedDelay(this::runOnce, 1, 1, TimeUnit.SECONDS);
	}

	/** Called only at the successful end of QuestService.finishQuest, not onQuestCompleted (also used by GM setters). */
	public static void observeNativeCompletion(QuestEnv env, QuestState state, int previousCount) {
		if (!flagsEnabled() || env.getQuestId() != QuestCompletionEvidence.QUEST) return;
		try {
			if (!(env.getVisibleObject() instanceof Npc npc)
				|| !sourceEligible(env.getQuestId(), previousCount, state, npc.getNpcId(), env.getDialogActionId())) return;
			Player owner = env.getPlayer();
			if (owner == null || owner.isStaff() || owner.getClientConnection() == null || !npc.isSpawned() || npc.isDead()
				|| owner.getWorldId() != npc.getWorldId() || owner.getInstanceId() != npc.getInstanceId()
				|| com.aionemu.gameserver.world.World.getInstance().findVisibleObject(npc.getObjectId()) != npc
				|| PositionUtil.getDistance(owner, npc) > QuestCompletionEvidence.MAX_DISTANCE) return;
			CompanionService.getInstance().observeQuestCompletion(owner, state, getInstance());
		} catch (RuntimeException failure) {
			log.warn("W0 observation skipped; native quest result remains unchanged", failure);
		}
	}
	public static boolean sourceEligible(int questId, int previousCount, QuestState state, int npcId, int dialog) {
		return questId == QuestCompletionEvidence.QUEST && previousCount == 0 && state != null && state.getQuestId() == questId
			&& state.getStatus() == QuestStatus.COMPLETE && state.getCompleteCount() == 1 && state.getLastCompleteTime() != null
			&& npcId == QuestCompletionEvidence.NPC && dialog == SELECTED_QUEST_NOREWARD;
	}
	public String realm() { return realm; }
	public boolean offer(QuestCompletionEvidence evidence) {
		if (closed.get() || !enabled.getAsBoolean() || !realm.equals(evidence.realm())) return false;
		Queued value = new Queued(evidence, 0);
		boolean accepted = queue.offer(value);
		if (closed.get()) { queue.remove(value); return false; }
		if (!accepted) dropped.incrementAndGet();
		return accepted;
	}
	public void skippedWitness() { dropped.incrementAndGet(); }

	void runOnce() {
		if (closed.get() || !enabled.getAsBoolean() || !QuestCompletionEvidence.validRealm(realm) || !worker.tryLock()) return;
		try {
			if (closed.get() || clock.getAsLong() < retryAfter) return;
			for (int i = 0; i < 8 && !closed.get() && enabled.getAsBoolean(); i++) {
				Queued value = queue.poll();
				if (value == null) break;
				try {
					if (store.saveCandidate(value.evidence())) persisted.incrementAndGet();
					else { conflicts.incrementAndGet(); log.warn("W0 source conflict owner={}", value.evidence().ownerId()); }
				} catch (Exception failure) {
					if (value.attempts() >= 2 || !queue.offer(new Queued(value.evidence(), value.attempts() + 1))) dropped.incrementAndGet();
					backoff(failure); return;
				}
			}
			if (!closed.get() && enabled.getAsBoolean()) store.reconcile(realm, clock.getAsLong(), 8);
		} catch (Exception failure) { backoff(failure); }
		finally { worker.unlock(); }
	}
	private void backoff(Exception failure) {
		failures.incrementAndGet(); retryAfter = clock.getAsLong() + 30_000;
		log.warn("W0 persistence unavailable; retry in 30s, native gameplay unchanged", failure);
	}
	public String status(Player owner) {
		return status(owner.getObjectId(), owner.getAccount().getId());
	}
	String status(int ownerId, int accountId) {
		if (closed.get() || !enabled.getAsBoolean()) return "memory=DISABLED";
		if (!QuestCompletionEvidence.validRealm(realm)) return "memory=UNCONFIGURED";
		try { return store.status(realm, ownerId, accountId); }
		catch (Exception failure) { failures.incrementAndGet(); return "memory=UNAVAILABLE"; }
	}
	public String diagnostics() {
		return "memoryQueue=" + queue.size() + ", memoryPersisted=" + persisted.get() + ", memoryDropped=" + dropped.get()
			+ ", memoryFailures=" + failures.get() + ", memoryConflicts=" + conflicts.get();
	}
	@Override public void close() {
		closed.set(true);
		synchronized (this) { if (executor != null) executor.shutdown(); }
		worker.lock(); // No new commit can start after this barrier returns.
		try { dropped.addAndGet(queue.size()); queue.clear(); }
		finally { worker.unlock(); }
	}
}
