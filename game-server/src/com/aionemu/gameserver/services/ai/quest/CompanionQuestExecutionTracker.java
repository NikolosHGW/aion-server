package com.aionemu.gameserver.services.ai.quest;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import com.aionemu.gameserver.questEngine.model.QuestStatus;

public final class CompanionQuestExecutionTracker {

	public static final long MIN_POLL_INTERVAL_MS = 500;
	public static final long MAX_POLL_INTERVAL_MS = 5000;

	private final QuestExecutionSession session;
	private final Supplier<QuestNativeSnapshot> nativeQuestReader;
	private final Supplier<QuestExecutionRuntimeContext> runtimeContextReader;
	private final Consumer<QuestExecutionTransition> transitionNotifier;
	private final LongSupplier currentTimeMillis;
	private final long pollIntervalMs;
	private QuestExecutionSnapshot currentSnapshot;
	private QuestExecutionSnapshot.SemanticFingerprint lastNotifiedFingerprint;
	private long nextPollAt;
	private long lastPollAt;
	private long pollCount;
	private long suppressedDuplicateCount;
	private boolean observedActive;

	public CompanionQuestExecutionTracker(QuestExecutionSession session, Supplier<QuestNativeSnapshot> nativeQuestReader,
		Supplier<QuestExecutionRuntimeContext> runtimeContextReader, Consumer<QuestExecutionTransition> transitionNotifier,
		LongSupplier currentTimeMillis, long pollIntervalMs) {
		this.session = Objects.requireNonNull(session);
		this.nativeQuestReader = Objects.requireNonNull(nativeQuestReader);
		this.runtimeContextReader = Objects.requireNonNull(runtimeContextReader);
		this.transitionNotifier = Objects.requireNonNull(transitionNotifier);
		this.currentTimeMillis = Objects.requireNonNull(currentTimeMillis);
		this.pollIntervalMs = requireValidPollInterval(pollIntervalMs);
	}

	public synchronized boolean pollIfDue() {
		long now = currentTimeMillis.getAsLong();
		if (now < nextPollAt)
			return false;
		QuestExecutionSnapshot next = evaluate(nativeQuestReader.get(), runtimeContextReader.get());
		QuestExecutionState previousState = currentSnapshot == null ? QuestExecutionState.NO_GOAL : currentSnapshot.executionState();
		currentSnapshot = next;
		lastPollAt = now;
		nextPollAt = now + pollIntervalMs;
		pollCount++;
		QuestExecutionSnapshot.SemanticFingerprint fingerprint = next.semanticFingerprint();
		if (!fingerprint.equals(lastNotifiedFingerprint)) {
			lastNotifiedFingerprint = fingerprint;
			transitionNotifier.accept(new QuestExecutionTransition(previousState, next.executionState(), next));
		} else {
			suppressedDuplicateCount++;
		}
		return true;
	}

	private QuestExecutionSnapshot evaluate(QuestNativeSnapshot nativeQuest, QuestExecutionRuntimeContext context) {
		QuestExecutionManifest manifest = session.plan().manifest();
		int progress = Math.clamp(nativeQuest.var0(), 0, manifest.requiredProgress());
		QuestExecutionState state;
		QuestExecutionNextStep nextStep;
		String reason;

		if (!context.identityMatches(session) || context.role() != QuestExecutionRole.PERSONAL_COMPANION) {
			state = QuestExecutionState.STALE;
			nextStep = QuestExecutionNextStep.REPLAN;
			reason = "SESSION_IDENTITY_MISMATCH";
		} else if (!context.active() || !context.sameMapAndInstance() || context.ownerMapId() != manifest.mapId()) {
			state = QuestExecutionState.BLOCKED;
			nextStep = QuestExecutionNextStep.NONE;
			reason = "RUNTIME_WORLD_INVARIANT";
		} else if (!nativeQuest.present()) {
			state = observedActive ? QuestExecutionState.ABANDONED : QuestExecutionState.CHOSEN_WAITING_ACCEPTANCE;
			nextStep = observedActive ? QuestExecutionNextStep.REPLAN : QuestExecutionNextStep.ACCEPT_AT_START_NPC;
			reason = observedActive ? "NATIVE_QUEST_REMOVED" : "WAITING_NATIVE_ACCEPTANCE";
		} else if (nativeQuest.status() == QuestStatus.START) {
			observedActive = true;
			if (progress >= manifest.requiredProgress()) {
				state = QuestExecutionState.READY_TO_TURN_IN;
				nextStep = QuestExecutionNextStep.RETURN_TO_END_NPC;
				reason = "OBJECTIVE_COMPLETE";
			} else {
				state = QuestExecutionState.ACTIVE_OBJECTIVE;
				nextStep = QuestExecutionNextStep.KILL_TARGETS;
				reason = "NATIVE_QUEST_ACTIVE";
			}
		} else if (nativeQuest.status() == QuestStatus.REWARD) {
			observedActive = true;
			state = QuestExecutionState.READY_TO_TURN_IN;
			nextStep = QuestExecutionNextStep.RETURN_TO_END_NPC;
			reason = "NATIVE_QUEST_REWARD";
		} else if (nativeQuest.status() == QuestStatus.COMPLETE) {
			observedActive = true;
			state = QuestExecutionState.COMPLETED;
			nextStep = QuestExecutionNextStep.QUEST_COMPLETE;
			reason = "NATIVE_QUEST_COMPLETE";
		} else {
			state = QuestExecutionState.BLOCKED;
			nextStep = QuestExecutionNextStep.NONE;
			reason = "UNSUPPORTED_NATIVE_QUEST_STATUS";
		}

		return new QuestExecutionSnapshot(nativeQuest.present(), nativeQuest.status(), nativeQuest.var0(), progress, manifest.requiredProgress(),
			nativeQuest.completeCount(), context.ownerObjectId(), context.companionObjectId(), context.companionSessionId(), context.role(),
			context.ownerMapId(), context.ownerInstanceId(), context.companionMapId(), context.companionInstanceId(), state, nextStep, reason);
	}

	public synchronized QuestExecutionSnapshot currentSnapshot() {
		return currentSnapshot;
	}

	public QuestExecutionSession session() {
		return session;
	}

	public synchronized long lastPollAt() {
		return lastPollAt;
	}

	public synchronized long pollCount() {
		return pollCount;
	}

	public synchronized long suppressedDuplicateCount() {
		return suppressedDuplicateCount;
	}

	public long pollIntervalMs() {
		return pollIntervalMs;
	}

	public static long requireValidPollInterval(long value) {
		if (value < MIN_POLL_INTERVAL_MS || value > MAX_POLL_INTERVAL_MS)
			throw new IllegalArgumentException("Quest execution poll interval must be between 500 and 5000 ms");
		return value;
	}
}
