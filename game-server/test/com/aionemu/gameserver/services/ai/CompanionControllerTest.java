package com.aionemu.gameserver.services.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class CompanionControllerTest {

	private final TestContext context = new TestContext();
	private final TestGateway gateway = new TestGateway();
	private final AtomicLong clock = new AtomicLong();
	private final AtomicReference<String> removalReason = new AtomicReference<>();
	private final CompanionController controller = new CompanionController(context, gateway,
		new CompanionFollowSettings(6, 3, 2, 400, 1000), removalReason::set, clock::get);

	@Test
	void summonAssociationUsesTheCorrectOwner() {
		assertEquals(77, controller.getOwnerObjectId());
		assertEquals("Owner", controller.getOwnerName());
	}

	@Test
	void followStartsAfterStartDistance() {
		context.distance = 7;

		controller.tick();

		assertEquals(1, gateway.startCount);
		assertEquals(CompanionMode.FOLLOWING, controller.getMode());
	}

	@Test
	void followStopsInsideStopDistance() {
		context.distance = 7;
		controller.tick();
		context.distance = 2;
		clock.addAndGet(400);

		controller.tick();

		assertEquals(1, gateway.startCount);
		assertEquals(1, gateway.stopCount);
	}

	@Test
	void hysteresisPreventsStartStopJitter() {
		context.distance = 4;

		for (int i = 0; i < 10; i++) {
			controller.tick();
			clock.addAndGet(400);
		}

		assertEquals(0, gateway.startCount);
		assertEquals(0, gateway.stopCount);
	}

	@Test
	void stayPreventsAutomaticMovement() {
		context.distance = 20;

		controller.stay();
		controller.tick();

		assertEquals(CompanionMode.STAYING, controller.getMode());
		assertEquals(0, gateway.startCount);
		assertEquals(1, gateway.stopCount);
	}

	@Test
	void followAfterStayResumesMovement() {
		context.distance = 20;
		controller.stay();

		controller.follow();
		controller.tick();

		assertEquals(CompanionMode.FOLLOWING, controller.getMode());
		assertEquals(1, gateway.startCount);
	}

	@Test
	void blockedLineOfSightNeverStartsMovementAndRetriesAtLimitedRate() {
		context.distance = 20;
		gateway.allowed = false;
		gateway.blockedReason = "line-of-sight";

		controller.tick();
		clock.addAndGet(500);
		controller.tick();

		assertEquals(CompanionMode.BLOCKED, controller.getMode());
		assertEquals("line-of-sight", controller.getLastBlockedReason());
		assertEquals(1, gateway.checkCount);
		assertEquals(0, gateway.startCount);
		assertEquals(1, gateway.stopCount);
	}

	@Test
	void ownerDisconnectRequestsCleanup() {
		context.connected = false;

		controller.tick();

		assertEquals("owner-disconnected", removalReason.get());
		assertEquals(0, gateway.startCount);
	}

	@Test
	void ownerMapOrInstanceChangeRequestsCleanup() {
		context.ownerInstanceId = 2;

		controller.tick();

		assertEquals("owner-map-or-instance-changed", removalReason.get());
		assertEquals(0, gateway.startCount);
	}

	@Test
	void ownerDeathStopsWithoutRequestingIndependentAction() {
		context.dead = true;

		controller.tick();

		assertEquals(CompanionMode.BLOCKED, controller.getMode());
		assertEquals("owner-dead", controller.getLastBlockedReason());
		assertNull(removalReason.get());
		assertEquals(0, gateway.startCount);
		assertEquals(1, gateway.stopCount);
	}

	private static final class TestContext implements CompanionContext {

		private boolean connected = true;
		private boolean inWorld = true;
		private boolean spawned = true;
		private boolean companionInWorld = true;
		private boolean companionSpawned = true;
		private boolean dead;
		private int ownerMapId = 1;
		private int ownerInstanceId = 1;
		private int companionMapId = 1;
		private int companionInstanceId = 1;
		private double distance;

		@Override
		public int ownerObjectId() {
			return 77;
		}

		@Override
		public String ownerName() {
			return "Owner";
		}

		@Override
		public boolean ownerConnected() {
			return connected;
		}

		@Override
		public boolean ownerInWorld() {
			return inWorld;
		}

		@Override
		public boolean ownerSpawned() {
			return spawned;
		}

		@Override
		public boolean ownerDead() {
			return dead;
		}

		@Override
		public int ownerMapId() {
			return ownerMapId;
		}

		@Override
		public int ownerInstanceId() {
			return ownerInstanceId;
		}

		@Override
		public float ownerX() {
			return 10;
		}

		@Override
		public float ownerY() {
			return 20;
		}

		@Override
		public float ownerZ() {
			return 30;
		}

		@Override
		public byte ownerHeading() {
			return 0;
		}

		@Override
		public boolean companionInWorld() {
			return companionInWorld;
		}

		@Override
		public boolean companionSpawned() {
			return companionSpawned;
		}

		@Override
		public int companionMapId() {
			return companionMapId;
		}

		@Override
		public int companionInstanceId() {
			return companionInstanceId;
		}

		@Override
		public double distanceToOwner() {
			return distance;
		}
	}

	private static final class TestGateway implements PlayerActionGateway {

		private boolean allowed = true;
		private String blockedReason = "";
		private int checkCount;
		private int startCount;
		private int stopCount;

		@Override
		public MovementCheck checkMovement(float x, float y, float z) {
			checkCount++;
			return allowed ? MovementCheck.allowed(x, y, z) : MovementCheck.blocked(blockedReason);
		}

		@Override
		public boolean startMove(MovementCheck movement) {
			startCount++;
			return movement.allowed();
		}

		@Override
		public void stopMove() {
			stopCount++;
		}
	}
}
