package com.aionemu.gameserver.services.ai.quest;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.questEngine.model.QuestStatus;

class CompanionQuestExecutionTrackerTest {

	@Test
	void waitingBeforeAcceptanceThenTracksNativeProgressAndCompletion() {
		Fixture fixture = new Fixture(QuestNativeSnapshot.absent());
		assertEquals(QuestExecutionState.CHOSEN_WAITING_ACCEPTANCE, fixture.poll().executionState());

		fixture.nativeQuest.set(nativeQuest(QuestStatus.START, 0));
		assertEquals(QuestExecutionState.ACTIVE_OBJECTIVE, fixture.advanceAndPoll().executionState());
		for (int progress = 1; progress <= 2; progress++) {
			fixture.nativeQuest.set(nativeQuest(QuestStatus.START, progress));
			QuestExecutionSnapshot snapshot = fixture.advanceAndPoll();
			assertEquals(QuestExecutionState.ACTIVE_OBJECTIVE, snapshot.executionState());
			assertEquals(progress, snapshot.currentProgress());
		}
		fixture.nativeQuest.set(nativeQuest(QuestStatus.START, 3));
		assertEquals(QuestExecutionState.READY_TO_TURN_IN, fixture.advanceAndPoll().executionState());
		fixture.nativeQuest.set(nativeQuest(QuestStatus.REWARD, 3));
		assertEquals(QuestExecutionState.READY_TO_TURN_IN, fixture.advanceAndPoll().executionState());
		fixture.nativeQuest.set(new QuestNativeSnapshot(true, QuestStatus.COMPLETE, 0, 1));
		assertEquals(QuestExecutionState.COMPLETED, fixture.advanceAndPoll().executionState());
	}

	@Test
	void absenceIsAbandonedOnlyAfterAnActiveStateWasObservedInThisSession() {
		Fixture neverAccepted = new Fixture(QuestNativeSnapshot.absent());
		assertEquals(QuestExecutionState.CHOSEN_WAITING_ACCEPTANCE, neverAccepted.poll().executionState());
		assertEquals(QuestExecutionState.CHOSEN_WAITING_ACCEPTANCE, neverAccepted.advanceAndPoll().executionState());

		Fixture accepted = new Fixture(nativeQuest(QuestStatus.START, 1));
		assertEquals(QuestExecutionState.ACTIVE_OBJECTIVE, accepted.poll().executionState());
		accepted.nativeQuest.set(QuestNativeSnapshot.absent());
		assertEquals(QuestExecutionState.ABANDONED, accepted.advanceAndPoll().executionState());
	}

	@Test
	void activeQuestCanAttachImmediatelyWithoutInventingProgress() {
		Fixture fixture = new Fixture(nativeQuest(QuestStatus.START, 2));
		QuestExecutionSnapshot snapshot = fixture.poll();
		assertEquals(QuestExecutionState.ACTIVE_OBJECTIVE, snapshot.executionState());
		assertEquals(2, snapshot.currentProgress());
		assertEquals(QuestExecutionNextStep.KILL_TARGETS, snapshot.nextStep());
	}

	@Test
	void pollBudgetAndSemanticFingerprintSuppressDuplicateMessages() {
		Fixture fixture = new Fixture(nativeQuest(QuestStatus.START, 1));
		assertTrue(fixture.tracker.pollIfDue());
		assertFalse(fixture.tracker.pollIfDue());
		assertEquals(1, fixture.transitions.size());
		fixture.now.addAndGet(1000);
		assertTrue(fixture.tracker.pollIfDue());
		assertEquals(1, fixture.transitions.size());
		assertEquals(1, fixture.tracker.suppressedDuplicateCount());
		assertEquals(2, fixture.tracker.pollCount());
	}

	@Test
	void mapOrLifecycleFailureBlocksAndIdentityMismatchIsStale() {
		Fixture fixture = new Fixture(nativeQuest(QuestStatus.START, 1));
		fixture.context.set(context(10, 20, 30, 210010000, 1, 210010000, 2, true));
		assertEquals(QuestExecutionState.BLOCKED, fixture.poll().executionState());

		Fixture stale = new Fixture(nativeQuest(QuestStatus.START, 1));
		stale.context.set(context(11, 20, 30, 210010000, 1, 210010000, 1, true));
		assertEquals(QuestExecutionState.STALE, stale.poll().executionState());
	}

	@Test
	void pollIntervalIsBounded() {
		assertEquals(500, CompanionQuestExecutionTracker.requireValidPollInterval(500));
		assertEquals(5000, CompanionQuestExecutionTracker.requireValidPollInterval(5000));
		assertThrows(IllegalArgumentException.class, () -> CompanionQuestExecutionTracker.requireValidPollInterval(499));
		assertThrows(IllegalArgumentException.class, () -> CompanionQuestExecutionTracker.requireValidPollInterval(5001));
	}

	@Test
	void runtimeClearIsIdempotentAndDropsManifestReferences() {
		CompanionQuestExecutionRuntime runtime = new CompanionQuestExecutionRuntime(10, 20, 30);
		AtomicLong now = new AtomicLong(1000);
		runtime.attach(executionPlan(), () -> QuestNativeSnapshot.absent(), () -> context(10, 20, 30, 210010000, 1, 210010000, 1, true),
			_ -> {}, now::get, 1000);
		assertTrue(runtime.isPresent());
		assertTrue(runtime.clear("dismiss"));
		assertFalse(runtime.clear("dismiss-again"));
		assertFalse(runtime.isPresent());
		assertTrue(runtime.authorizationView().isEmpty());
	}

	private static QuestNativeSnapshot nativeQuest(QuestStatus status, int var0) {
		return new QuestNativeSnapshot(true, status, var0, 0);
	}

	private static QuestExecutionRuntimeContext context(int ownerId, int companionId, long sessionId, int ownerMap, int ownerInstance,
		int companionMap, int companionInstance, boolean active) {
		return new QuestExecutionRuntimeContext(ownerId, companionId, sessionId, QuestExecutionRole.PERSONAL_COMPANION, ownerMap, ownerInstance,
			companionMap, companionInstance, active, active, active);
	}

	private static QuestExecutionPlan executionPlan() {
		QuestExecutionManifest manifest = QuestExecutionManifest.curated1102();
		QuestGoalPlan goal = new QuestGoalPlan(1102, "Kerubar Hunt", "Kerubar Hunt", "test", 1, 1, List.of(1101),
			List.of(
				new QuestGoalStep(QuestGoalStep.Type.TALK_TO_START_NPC, List.of(203057), List.of("Mires"), 1,
					new QuestGoalStep.ReferenceCoordinate(210010000, 203057, 0, 1141, 1032, 128.875f)),
				new QuestGoalStep(QuestGoalStep.Type.KILL_NPC_SET, List.of(210133, 210134), List.of("Kerub 1", "Kerub 2"), 3,
					new QuestGoalStep.ReferenceCoordinate(210010000, 210133, 0, 1038.58f, 989.604f, 129.484f)),
				new QuestGoalStep(QuestGoalStep.Type.REPORT_TO_END_NPC, List.of(203057), List.of("Mires"), 1,
					new QuestGoalStep.ReferenceCoordinate(210010000, 203057, 0, 1141, 1032, 128.875f))),
			List.of("kinah=400, exp=180"), List.of("test"));
		return new QuestExecutionPlan(goal, manifest);
	}

	private static final class Fixture {

		private final AtomicLong now = new AtomicLong(1000);
		private final AtomicReference<QuestNativeSnapshot> nativeQuest;
		private final AtomicReference<QuestExecutionRuntimeContext> context = new AtomicReference<>(
			context(10, 20, 30, 210010000, 1, 210010000, 1, true));
		private final List<QuestExecutionTransition> transitions = new ArrayList<>();
		private final CompanionQuestExecutionTracker tracker;

		private Fixture(QuestNativeSnapshot initial) {
			nativeQuest = new AtomicReference<>(initial);
			tracker = new CompanionQuestExecutionTracker(new QuestExecutionSession(10, 20, 30, QuestExecutionRole.PERSONAL_COMPANION,
				executionPlan()), nativeQuest::get, context::get, transitions::add, now::get, 1000);
		}

		private QuestExecutionSnapshot poll() {
			assertTrue(tracker.pollIfDue());
			return tracker.currentSnapshot();
		}

		private QuestExecutionSnapshot advanceAndPoll() {
			now.addAndGet(1000);
			return poll();
		}
	}
}
