package com.aionemu.gameserver.services.ai.quest;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class CompanionQuestExecutionRuntime {

	private final int ownerObjectId;
	private final int companionObjectId;
	private final long companionSessionId;
	private CompanionQuestExecutionTracker tracker;
	private String lastDetachReason = "none";

	public CompanionQuestExecutionRuntime(int ownerObjectId, int companionObjectId, long companionSessionId) {
		if (ownerObjectId <= 0 || companionObjectId <= 0 || companionSessionId <= 0)
			throw new IllegalArgumentException("Invalid companion quest execution runtime identity");
		this.ownerObjectId = ownerObjectId;
		this.companionObjectId = companionObjectId;
		this.companionSessionId = companionSessionId;
	}

	public synchronized QuestExecutionSnapshot attach(QuestExecutionPlan plan, Supplier<QuestNativeSnapshot> nativeQuestReader,
		Supplier<QuestExecutionRuntimeContext> runtimeContextReader, Consumer<QuestExecutionTransition> transitionNotifier,
		LongSupplier currentTimeMillis, long pollIntervalMs) {
		QuestExecutionSession session = new QuestExecutionSession(ownerObjectId, companionObjectId, companionSessionId,
			QuestExecutionRole.PERSONAL_COMPANION, plan);
		tracker = new CompanionQuestExecutionTracker(session, nativeQuestReader, runtimeContextReader, transitionNotifier, currentTimeMillis,
			pollIntervalMs);
		lastDetachReason = "none";
		tracker.pollIfDue();
		return tracker.currentSnapshot();
	}

	public synchronized boolean pollIfDue() {
		return tracker != null && tracker.pollIfDue();
	}

	public synchronized boolean clear(String reason) {
		boolean changed = tracker != null;
		tracker = null;
		lastDetachReason = reason;
		return changed;
	}

	public synchronized Optional<AuthorizationView> authorizationView() {
		if (tracker == null || tracker.currentSnapshot() == null)
			return Optional.empty();
		return Optional.of(new AuthorizationView(tracker.session(), tracker.currentSnapshot()));
	}

	public synchronized boolean isPresent() {
		return tracker != null;
	}

	public synchronized String status(long now, int ownerMapId, int ownerInstanceId, int companionMapId, int companionInstanceId,
		QuestExecutionState stateWhenAbsent) {
		if (tracker == null) {
			return "executionSessionPresent=false, executionState=" + stateWhenAbsent + ", executionQuestId=none"
				+ ", nativeQuestPresent=false, nativeQuestStatus=none, currentProgress=0, requiredProgress=0, nextStep="
				+ QuestExecutionNextStep.NONE + ", lastTransitionReason=" + lastDetachReason + ", lastPollAgeMs=-1"
				+ ", executionOwnerObjectId=" + ownerObjectId + ", executionCompanionObjectId=" + companionObjectId
				+ ", executionCompanionSessionId=" + companionSessionId + ", ownerMapId=" + ownerMapId + ", ownerInstanceId=" + ownerInstanceId
				+ ", companionMapId=" + companionMapId + ", companionInstanceId=" + companionInstanceId
				+ ", manifestTargetIds=[], approvedSpawnCount=0";
		}
		QuestExecutionSnapshot snapshot = tracker.currentSnapshot();
		QuestExecutionManifest manifest = tracker.session().plan().manifest();
		long age = tracker.lastPollAt() == 0 ? -1 : Math.max(0, now - tracker.lastPollAt());
		return "executionSessionPresent=true, executionState=" + snapshot.executionState() + ", executionQuestId=" + tracker.session().questId()
			+ ", nativeQuestPresent=" + snapshot.nativeQuestPresent() + ", nativeQuestStatus="
			+ (snapshot.nativeQuestStatus() == null ? "none" : snapshot.nativeQuestStatus()) + ", currentProgress=" + snapshot.currentProgress()
			+ ", requiredProgress=" + snapshot.requiredProgress() + ", nextStep=" + snapshot.nextStep() + ", lastTransitionReason="
			+ snapshot.reason() + ", lastPollAgeMs=" + age + ", pollIntervalMs=" + tracker.pollIntervalMs() + ", pollCount=" + tracker.pollCount()
			+ ", suppressedDuplicateNotifications=" + tracker.suppressedDuplicateCount() + ", executionOwnerObjectId=" + ownerObjectId
			+ ", executionCompanionObjectId=" + companionObjectId + ", executionCompanionSessionId=" + companionSessionId
			+ ", ownerMapId=" + ownerMapId + ", ownerInstanceId=" + ownerInstanceId + ", companionMapId=" + companionMapId
			+ ", companionInstanceId=" + companionInstanceId + ", manifestTargetIds=" + manifest.targetIds()
			+ ", approvedSpawnCount=" + manifest.approvedSpawnCount();
	}

	public record AuthorizationView(QuestExecutionSession session, QuestExecutionSnapshot snapshot) {
	}
}
