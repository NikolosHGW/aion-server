package com.aionemu.gameserver.services.ai.action;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class ActionSessionTest {
	@Test void lateSkillCompletionCannotCompleteANewAction() {
		var first = actions.nextId();
		actions.start(first, "cast1", 200, this::run, stops::incrementAndGet);
		var second = actions.nextId();
		actions.start(second, "cast2", 200, this::run, stops::incrementAndGet);
		assertNull(actions.finish(first, ActionSession.State.SUCCEEDED, "late-hit"));
		assertEquals(ActionSession.State.RUNNING, actions.current().state());
		actions.finish(second, ActionSession.State.SUCCEEDED, "native-effect-resolved");
		actions.cancel(second, "late-cancel");
		assertEquals(ActionSession.State.SUCCEEDED, actions.current().state());
		assertEquals(1, stops.get());
	}
	@Test void cancelledSkillCannotBeResurrectedByItsDelayedEffect() {
		var id = actions.nextId();
		actions.start(id, "cast", 200, this::run, stops::incrementAndGet);
		actions.cancel(id, "owner-dismissed");
		actions.finish(id, ActionSession.State.SUCCEEDED, "late-hit");
		assertEquals(ActionSession.State.CANCELLED, actions.current().state());
	}
	@Test void synchronousInstantSkillCompletionSurvivesStartReturn() {
		var id = actions.nextId();
		actions.start(id, "cast", 200, () -> {
			actions.finish(id, ActionSession.State.SUCCEEDED, "native-effect-resolved");
			return run();
		}, stops::incrementAndGet);
		assertEquals(ActionSession.State.SUCCEEDED, actions.current().state());
	}
	private final AtomicLong clock = new AtomicLong(100);
	private final AtomicBoolean usable = new AtomicBoolean(true);
	private final AtomicInteger executions = new AtomicInteger(), stops = new AtomicInteger();
	private final ActionSession actions = new ActionSession(usable::get, clock::get, 2);
	private ActionSession.Outcome run() {
		executions.incrementAndGet();
		return new ActionSession.Outcome(ActionSession.State.RUNNING, "native-started");
	}
	@Test void startIsNotArrivalAndDuplicateDoesNotExecuteAgain() {
		var id = actions.nextId();
		var first = actions.start(id, "move", 200, this::run, stops::incrementAndGet);
		assertEquals(ActionSession.State.RUNNING, first.state());
		assertEquals(first, actions.start(id, "move", 200, this::run, stops::incrementAndGet));
		assertEquals(1, executions.get());
		actions.poll(true, false);
		assertEquals(ActionSession.State.SUCCEEDED, actions.current().state());
		actions.cancel(id, "late-cancel");
		assertEquals(0, stops.get());
	}
	@Test void oldCancelNeverStopsReplacementAndHistoryIsBounded() {
		var old = actions.nextId();
		actions.start(old, "move1", 200, this::run, stops::incrementAndGet);
		var next = actions.nextId();
		actions.start(next, "move2", 200, this::run, stops::incrementAndGet);
		assertEquals(1, stops.get());
		actions.cancel(old, "late");
		assertEquals(next, actions.current().id());
		assertEquals(1, stops.get());
		actions.start(actions.nextId(), "move3", 200, this::run, stops::incrementAndGet);
		assertEquals(2, actions.retainedReceipts());
		assertEquals(ActionSession.State.REJECTED, actions.start(old, "move1", 200, this::run, stops::incrementAndGet).state());
		assertEquals(3, executions.get());
	}
	@Test void changedRequestAndNewIncarnationRejectOldId() {
		var id = actions.nextId();
		actions.start(id, "move", 200, this::run, stops::incrementAndGet);
		assertEquals(ActionSession.State.REJECTED, actions.start(id, "purchase", 200, this::run, stops::incrementAndGet).state());
		var restarted = new ActionSession(() -> true, clock::get, 2);
		restarted.nextId();
		assertEquals(ActionSession.State.REJECTED, restarted.start(id, "move", 200, this::run, stops::incrementAndGet).state());
		assertEquals(1, executions.get());
	}
	@Test void deadlineAndClosedSessionCancelExactlyOnce() {
		var id = actions.nextId();
		actions.start(id, "move", 200, this::run, stops::incrementAndGet);
		clock.set(200);
		actions.poll(false, true);
		assertEquals(ActionSession.State.CANCELLED, actions.current().state());
		assertEquals("deadline-expired", actions.current().reason());
		actions.close(); actions.close(); actions.cancel(id, "repeat");
		assertEquals(1, stops.get());
		assertEquals(ActionSession.State.REJECTED, actions.start(actions.nextId(), "next", 300, this::run, stops::incrementAndGet).state());
	}
	@Test void expiredAndUnusableRequestsHaveNoSideEffects() {
		assertEquals(ActionSession.State.REJECTED, actions.start(actions.nextId(), "move", 99, this::run, stops::incrementAndGet).state());
		usable.set(false);
		assertEquals(ActionSession.State.REJECTED, actions.start(actions.nextId(), "move", 200, this::run, stops::incrementAndGet).state());
		assertEquals(0, executions.get());
	}
	@Test void nativeInterruptionIsFailureAndOwnersAreIndependent() {
		actions.start(actions.nextId(), "move", 200, this::run, stops::incrementAndGet);
		var second = new ActionSession(() -> true, clock::get, 2);
		second.start(second.nextId(), "move", 200, this::run, stops::incrementAndGet);
		actions.poll(false, false);
		assertEquals(ActionSession.State.FAILED, actions.current().state());
		assertEquals(ActionSession.State.RUNNING, second.current().state());
	}
	@Test void failedNativeStartIsNeverRetriedByReplayingId() {
		var id = actions.nextId();
		assertThrows(IllegalStateException.class, () -> actions.start(id, "move", 200, () -> {
			executions.incrementAndGet(); throw new IllegalStateException("native error");
		}, stops::incrementAndGet));
		assertEquals(ActionSession.State.FAILED, actions.start(id, "move", 200, this::run, stops::incrementAndGet).state());
		assertEquals(1, executions.get()); assertEquals(1, stops.get());
	}
	@Test void synchronousRemovalCannotBeOverwrittenByStartResult() {
		actions.start(actions.nextId(), "move", 200, () -> {
			actions.close(); return run();
		}, stops::incrementAndGet);
		assertEquals(ActionSession.State.CANCELLED, actions.current().state());
	}
	@Test void failingCancellationClosesAdmission() {
		actions.start(actions.nextId(), "move", 200, this::run, () -> { throw new IllegalStateException("stop failed"); });
		assertThrows(IllegalStateException.class, actions::close);
		assertEquals(ActionSession.State.FAILED, actions.current().state());
		assertEquals(ActionSession.State.REJECTED, actions.start(actions.nextId(), "move", 200, this::run, () -> {}).state());
	}
}
