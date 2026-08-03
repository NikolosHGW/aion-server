package com.aionemu.gameserver.services.ai.combat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.services.ai.SyntheticPlayerRole;

class CompanionCombatControllerTest {

	private final AtomicLong clock = new AtomicLong(1_000);
	private final AtomicBoolean featureEnabled = new AtomicBoolean(true);
	private final TestSignalSource signals = new TestSignalSource();
	private final TestGateway gateway = new TestGateway();
	private final CompanionCombatController controller = new CompanionCombatController(10, 20, 30, signals, gateway, featureEnabled::get,
		clock::get, 3_000);

	@Test
	void assistDefaultsOffAndOnOffAreIdempotent() {
		assertFalse(controller.isAssistEnabled());
		assertTrue(controller.enableAssist());
		assertFalse(controller.enableAssist());
		assertTrue(controller.isAssistEnabled());
		assertTrue(controller.disableAssist("test"));
		assertFalse(controller.disableAssist("test"));
		assertFalse(controller.isAssistEnabled());
		assertFalse(controller.isObserverAttached());
	}

	@Test
	void exactlyOneGatewayCallIsMadeForOneUniqueEvent() {
		controller.enableAssist();
		signals.add(signal(1, 30, 1_000));

		controller.tick();
		controller.tick();

		assertEquals(1, gateway.calls);
		assertTrue(controller.status().contains("lastCombatEventId=1"));
	}

	@Test
	void duplicateEventIsRejectedBeforeGateway() {
		controller.enableAssist();
		signals.add(signal(7, 30, 1_000));
		signals.add(signal(7, 30, 1_000));

		controller.tick();
		controller.tick();

		assertEquals(1, gateway.calls);
		assertTrue(controller.status().contains("lastCombatResult=DUPLICATE_EVENT"));
	}

	@Test
	void expiredEventIsRejectedBeforeGateway() {
		controller.enableAssist();
		signals.add(signal(1, 30, -2_001));

		controller.tick();

		assertEquals(0, gateway.calls);
		assertTrue(controller.status().contains("lastCombatResult=EVENT_EXPIRED"));
	}

	@Test
	void sessionMismatchIsRejectedBeforeGateway() {
		controller.enableAssist();
		signals.add(signal(1, 31, 1_000));

		controller.tick();

		assertEquals(0, gateway.calls);
		assertTrue(controller.status().contains("lastCombatResult=SESSION_MISMATCH"));
	}

	@Test
	void roleMismatchIsRejectedBeforeGateway() {
		controller.enableAssist();
		OwnerAttackSignal valid = signal(1, 30, 1_000);
		signals.add(new OwnerAttackSignal(valid.eventId(), valid.sessionId(), valid.ownerObjectId(), valid.companionObjectId(), valid.targetObjectId(),
			valid.targetTemplateId(), valid.mapId(), valid.instanceId(), valid.skillId(), valid.occurredAt(), SyntheticPlayerRole.ROUTE_SPIKE,
			valid.owner(), valid.companion(), valid.target()));

		controller.tick();

		assertEquals(0, gateway.calls);
		assertTrue(controller.status().contains("lastCombatResult=ROLE_MISMATCH"));
	}

	@Test
	void runtimeDisableDisarmsAndClearsPendingEvent() {
		controller.enableAssist();
		signals.add(signal(1, 30, 1_000));
		featureEnabled.set(false);

		controller.tick();

		assertFalse(controller.isAssistEnabled());
		assertFalse(controller.isObserverAttached());
		assertEquals(0, gateway.calls);
		assertTrue(signals.events.isEmpty());
	}

	@Test
	void removalDisarmsStopsGatewayAndRejectsFutureEnable() {
		controller.enableAssist();

		controller.beginRemoval("dismiss");

		assertFalse(controller.isAssistEnabled());
		assertFalse(controller.isObserverAttached());
		assertEquals(1, gateway.stopCalls);
		assertFalse(controller.enableAssist());
		assertTrue(controller.status().contains("combatRemoving=true"));
	}

	private OwnerAttackSignal signal(long eventId, long sessionId, long occurredAt) {
		return new OwnerAttackSignal(eventId, sessionId, 10, 20, 40, 210115, 210010000, 1, 0, occurredAt, SyntheticPlayerRole.COMPANION, null,
			null, null);
	}

	private static final class TestSignalSource implements CombatSignalSource {

		private final Queue<OwnerAttackSignal> events = new ArrayDeque<>();
		private boolean enabled;
		private boolean attached;

		void add(OwnerAttackSignal signal) {
			events.add(signal);
		}

		@Override
		public boolean enable() {
			if (enabled)
				return false;
			enabled = true;
			attached = true;
			return true;
		}

		@Override
		public boolean disable() {
			boolean changed = enabled;
			enabled = false;
			attached = false;
			clear();
			return changed;
		}

		@Override
		public boolean isEnabled() {
			return enabled;
		}

		@Override
		public boolean isObserverAttached() {
			return attached;
		}

		@Override
		public OwnerAttackSignal poll() {
			return events.poll();
		}

		@Override
		public void clear() {
			events.clear();
		}
	}

	private static final class TestGateway implements CombatActionGateway {

		private int calls;
		private int stopCalls;

		@Override
		public CombatResult basicHitOnce(OwnerAttackSignal signal) {
			calls++;
			return CombatResult.of(CombatStatus.HIT_STARTED, "test", signal);
		}

		@Override
		public void stop(String reason) {
			stopCalls++;
		}
	}
}
