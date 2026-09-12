package com.aionemu.gameserver.services.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.services.ai.PlayerActionGateway.MovementPhase;
import com.aionemu.gameserver.services.ai.PlayerActionGateway.MovementIntent;
import com.aionemu.gameserver.services.ai.PlayerActionGateway.MovementRejectionReason;
import com.aionemu.gameserver.services.ai.PlayerActionGateway.MovementResult;

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
	void blockedSpatialCandidatesNeverStartMovementAndRetryAtLimitedRate() {
		context.distance = 20;
		gateway.defaultCheckRejection = MovementRejectionReason.LINE_OF_SIGHT;

		controller.tick();
		clock.addAndGet(500);
		controller.tick();

		assertEquals(CompanionMode.BLOCKED, controller.getMode());
		assertEquals("LINE_OF_SIGHT", controller.getLastBlockedReason());
		assertEquals(3, gateway.checkCount);
		assertEquals(0, gateway.startCount);
		assertEquals(1, gateway.stopCount);
		assertTrue(controller.getMovementDiagnostics().contains("consecutiveMovementRetries=1"));
		assertTrue(controller.getMovementDiagnostics().contains("movementBlockedSince=0"));
	}

	@Test
	void boundedRecoveryUsesRearLeftAfterPrimarySpatialRejection() {
		context.distance = 20;
		gateway.rejectChecks(MovementRejectionReason.COLLISION, MovementRejectionReason.NONE);

		controller.tick();

		assertEquals(CompanionMode.FOLLOWING, controller.getMode());
		assertEquals(2, gateway.checkCount);
		assertEquals(1, gateway.startCount);
		assertEquals("", controller.getLastBlockedReason());
		assertTrue(controller.getMovementDiagnostics().contains("currentMovementRejection=NONE"));
		assertTrue(controller.getMovementDiagnostics().contains("lastMovementRejection=INITIAL_CHECK:COLLISION"));
		assertTrue(controller.getMovementDiagnostics().contains("lastMovementRejectionAttemptedDestination="));
		assertTrue(controller.getMovementDiagnostics().contains("lastMovementRejectionResolvedDestination="));
		assertTrue(controller.getMovementDiagnostics().contains("movementRecoveryCount=1"));
		assertTrue(controller.getMovementDiagnostics().contains("movementLastRecoveryCandidate=REAR_LEFT"));
	}

	@Test
	void continuingOnSameAlternateDoesNotInflateRecoveryCount() {
		context.distance = 20;
		gateway.rejectChecks(MovementRejectionReason.COLLISION, MovementRejectionReason.NONE,
			MovementRejectionReason.COLLISION, MovementRejectionReason.NONE);

		controller.tick();
		clock.addAndGet(400);
		controller.tick();

		assertEquals(4, gateway.checkCount);
		assertEquals(2, gateway.startCount);
		assertTrue(controller.getMovementDiagnostics().contains("movementRecoveryCount=1"));
	}

	@Test
	void nonSpatialRejectionDoesNotTryAlternateCandidates() {
		context.distance = 20;
		gateway.defaultCheckRejection = MovementRejectionReason.MOVEMENT_NOT_ALLOWED;

		controller.tick();

		assertEquals(CompanionMode.BLOCKED, controller.getMode());
		assertEquals(1, gateway.checkCount);
		assertEquals("MOVEMENT_NOT_ALLOWED", controller.getLastBlockedReason());
	}

	@Test
	void startRevalidationReasonIsNotCollapsed() {
		context.distance = 20;
		gateway.startRejection = MovementRejectionReason.MOVEMENT_NOT_ALLOWED;
		gateway.startRejectionPhase = MovementPhase.START_REVALIDATION;

		controller.tick();

		assertEquals(CompanionMode.BLOCKED, controller.getMode());
		assertEquals(1, gateway.checkCount);
		assertEquals(1, gateway.startCount);
		assertTrue(controller.getMovementDiagnostics().contains("currentMovementRejection=START_REVALIDATION:MOVEMENT_NOT_ALLOWED"));
	}

	@Test
	void retryReadsCurrentOwnerPositionAndRecordsAutomaticRecovery() {
		context.distance = 20;
		gateway.defaultCheckRejection = MovementRejectionReason.COLLISION;
		controller.tick();
		float firstAttemptX = gateway.attemptedX.get(0);

		context.ownerX = 100;
		gateway.defaultCheckRejection = MovementRejectionReason.NONE;
		clock.addAndGet(1000);
		controller.tick();

		assertEquals(CompanionMode.FOLLOWING, controller.getMode());
		assertNotEquals(firstAttemptX, gateway.attemptedX.get(3));
		assertEquals("", controller.getLastBlockedReason());
		assertTrue(controller.getMovementDiagnostics().contains("consecutiveMovementRetries=0"));
		assertTrue(controller.getMovementDiagnostics().contains("movementLastRecovery=1000"));
		assertTrue(controller.getMovementDiagnostics().contains("movementLastRecoveryCandidate=REAR"));
	}

	@Test
	void repeatedBlockedCyclesUseAtMostThreeCandidatesPerRetry() {
		context.distance = 20;
		gateway.defaultCheckRejection = MovementRejectionReason.DESTINATION_HAS_NO_GEODATA;

		controller.tick();
		clock.addAndGet(1000);
		controller.tick();

		assertEquals(6, gateway.checkCount);
		assertTrue(controller.getMovementDiagnostics().contains("consecutiveMovementRetries=2"));
	}

	@Test
	void stayClearsCurrentRejectionButKeepsHistoricalDiagnostics() {
		context.distance = 20;
		gateway.defaultCheckRejection = MovementRejectionReason.COLLISION;
		controller.tick();

		controller.stay();

		assertEquals(CompanionMode.STAYING, controller.getMode());
		assertEquals("", controller.getLastBlockedReason());
		assertTrue(controller.getMovementDiagnostics().contains("currentMovementRejection=NONE"));
		assertTrue(controller.getMovementDiagnostics().contains("lastMovementRejection=INITIAL_CHECK:COLLISION"));
	}

	@Test
	void significantSeparationUsesChronologicalOwnerBreadcrumb() {
		context.ownerX = 0;
		context.ownerY = 0;
		context.ownerZ = 0;
		context.distance = 2;
		controller.tick();

		clock.set(400);
		context.ownerX = 4;
		context.distance = 7;
		controller.tick();

		clock.set(800);
		context.ownerX = 8;
		context.distance = 13;
		controller.tick();

		assertEquals(MovementIntent.OWNER_BREADCRUMB, gateway.intents.getLast());
		assertEquals(4, gateway.attemptedX.getLast());
		assertTrue(controller.getMovementDiagnostics().contains("followStrategy=OWNER_BREADCRUMB"));
	}

	@Test
	void remoteOwnerLineOfSightFailureFallsBackToValidBreadcrumb() {
		buildContinuousTrailToFourMeters();
		gateway.ownerOffsetRejection = MovementRejectionReason.OWNER_LINE_OF_SIGHT;
		clock.set(800);
		context.ownerX = 8;
		context.distance = 10;

		controller.tick();

		assertEquals(List.of(MovementIntent.OWNER_OFFSET, MovementIntent.OWNER_BREADCRUMB),
			gateway.intents.subList(gateway.intents.size() - 2, gateway.intents.size()));
		assertEquals(CompanionMode.FOLLOWING, controller.getMode());
	}

	@Test
	void remoteCollisionFailureFallsBackToValidBreadcrumb() {
		buildContinuousTrailToFourMeters();
		gateway.ownerOffsetRejection = MovementRejectionReason.COLLISION;
		clock.set(800);
		context.ownerX = 8;
		context.distance = 10;

		controller.tick();

		assertEquals(MovementIntent.OWNER_BREADCRUMB, gateway.intents.getLast());
		assertEquals(CompanionMode.FOLLOWING, controller.getMode());
	}

	@Test
	void allBoundedBreadcrumbCandidatesBlockedAndSamplingContinuesBeforeRetry() {
		buildContinuousTrailToFourMeters();
		gateway.breadcrumbRejection = MovementRejectionReason.COLLISION;
		clock.set(800);
		context.ownerX = 8;
		context.distance = 13;
		controller.tick();
		String blockedStatus = controller.getMovementDiagnostics();

		clock.set(1_200);
		context.ownerX = 10;
		context.distance = 15;
		controller.tick();

		assertEquals(CompanionMode.BLOCKED, controller.getMode());
		assertTrue(blockedStatus.contains("currentMovementRejection=INITIAL_CHECK:COLLISION"));
		assertTrue(controller.getMovementDiagnostics().contains("breadcrumbCount=3"));
		assertEquals(8, gateway.attemptedX.getLast());
	}

	@Test
	void breadcrumbLineOfSightFailureRemainsBlocked() {
		buildContinuousTrailToFourMeters();
		gateway.breadcrumbRejection = MovementRejectionReason.LINE_OF_SIGHT;
		clock.set(800);
		context.ownerX = 8;
		context.distance = 13;

		controller.tick();

		assertEquals(CompanionMode.BLOCKED, controller.getMode());
		assertEquals("LINE_OF_SIGHT", controller.getLastBlockedReason());
		assertEquals(8, gateway.attemptedX.getLast());
	}

	@Test
	void blockedFirstBreadcrumbUsesValidatedLookAhead() {
		buildContinuousTrailToFourMeters();
		gateway.rejectChecks(MovementRejectionReason.NONE, MovementRejectionReason.DESTINATION_HAS_NO_GEODATA,
			MovementRejectionReason.NONE);
		clock.set(800);
		context.ownerX = 8;
		context.distance = 30;
		controller.tick();

		assertEquals(CompanionMode.FOLLOWING, controller.getMode());
		assertEquals(8, gateway.attemptedX.getLast());
		assertTrue(controller.getMovementDiagnostics().contains("followStrategy=OWNER_BREADCRUMB"));
		assertTrue(controller.getMovementDiagnostics().contains("breadcrumbShortcutSkippedCount=1"));
		assertEquals(2, gateway.startCount);
	}

	@Test
	void returningToNormalRangeRestoresOwnerOffsetStrategy() {
		buildContinuousTrailToFourMeters();
		clock.set(800);
		context.ownerX = 8;
		context.distance = 13;
		controller.tick();
		assertTrue(controller.getMovementDiagnostics().contains("followStrategy=OWNER_BREADCRUMB"));

		clock.set(1_200);
		context.companionX = 8;
		context.distance = 2;
		controller.tick();

		assertTrue(controller.getMovementDiagnostics().contains("followStrategy=OWNER_OFFSET"));
		assertFalse(controller.getMovementDiagnostics().contains("catchUpActive=true"));
	}

	@Test
	void discontinuousTrailDoesNotProduceBreadcrumbMovement() {
		buildContinuousTrailToFourMeters();
		clock.set(800);
		context.ownerX = 20;
		context.distance = 20;

		controller.tick();

		assertTrue(controller.getMovementDiagnostics().contains("breadcrumbDiscontinuity=XY_JUMP"));
		assertFalse(gateway.intents.contains(MovementIntent.OWNER_BREADCRUMB));
	}

	@Test
	void followSpeedMirrorsFastOwnerAndAddsBoundedCatchUp() {
		context.ownerSpeed = 9;
		context.companionSpeed = 6;
		context.distance = 8;
		controller.tick();

		assertEquals(9, gateway.effectiveSpeeds.getLast());

		clock.set(400);
		context.distance = 20;
		controller.tick();

		assertEquals(10, gateway.effectiveSpeeds.getLast());
		assertTrue(controller.getMovementDiagnostics().contains("catchUpActive=true"));
	}

	@Test
	void combatSuppressionUsesNativeLocomotionWithoutCatchUp() {
		TestContext combatContext = new TestContext();
		combatContext.distance = 20;
		combatContext.ownerSpeed = 9;
		combatContext.companionSpeed = 6;
		TestGateway combatGateway = new TestGateway();
		CompanionController combatController = new CompanionController(combatContext, combatGateway,
			new CompanionFollowSettings(6, 3, 2, 400, 1000), ignored -> { }, clock::get, () -> { }, () -> true);

		combatController.tick();

		assertTrue(Float.isNaN(combatGateway.effectiveSpeeds.getLast()));
		assertTrue(combatController.getMovementDiagnostics().contains("catchUpSuppression=COMBAT"));
		assertTrue(combatController.getMovementDiagnostics().contains("effectiveFollowSpeed=6.0"));
	}

	@Test
	void stayAndRemovalClearTrailAndLocomotionOverride() {
		buildContinuousTrailToFourMeters();
		int clearsBefore = gateway.clearSpeedCount;

		controller.stay();

		assertTrue(controller.getMovementDiagnostics().contains("breadcrumbCount=0"));
		assertTrue(gateway.clearSpeedCount > clearsBefore);

		controller.follow();
		context.ownerInstanceId = 2;
		controller.tick();
		assertEquals("owner-map-or-instance-changed", removalReason.get());
		assertTrue(controller.getMovementDiagnostics().contains("breadcrumbCount=0"));
	}

	@Test
	void manualFollowStartsANewTrailAndStopClearsLocomotionOverride() {
		buildContinuousTrailToFourMeters();
		assertFalse(controller.getMovementDiagnostics().contains("breadcrumbCount=0"));

		controller.follow();
		assertTrue(controller.getMovementDiagnostics().contains("breadcrumbCount=0"));
		int clearsBeforeStop = gateway.clearSpeedCount;

		controller.stop();
		assertTrue(gateway.clearSpeedCount > clearsBeforeStop);
	}

	@Test
	void manualFollowAndStopClearTransientFollowState() {
		buildContinuousTrailToFourMeters();
		controller.follow();

		assertTrue(controller.getMovementDiagnostics().contains("breadcrumbCount=0"));
		int clearsBeforeStop = gateway.clearSpeedCount;
		controller.stop();
		assertTrue(gateway.clearSpeedCount > clearsBeforeStop);
	}

	private void buildContinuousTrailToFourMeters() {
		context.ownerX = 0;
		context.ownerY = 0;
		context.ownerZ = 0;
		context.distance = 2;
		controller.tick();
		clock.set(400);
		context.ownerX = 4;
		context.distance = 7;
		controller.tick();
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
		assertEquals("OWNER_DEAD", controller.getLastBlockedReason());
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
		private float ownerX = 10;
		private float ownerY = 20;
		private float ownerZ = 30;
		private float companionX;
		private float companionY;
		private float companionZ;
		private float ownerSpeed = 6;
		private float companionSpeed = 6;
		private boolean groundMovement = true;
		private boolean movementAllowed = true;
		private byte ownerHeading;

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
			return ownerX;
		}

		@Override
		public float ownerY() {
			return ownerY;
		}

		@Override
		public float ownerZ() {
			return ownerZ;
		}

		@Override
		public byte ownerHeading() {
			return ownerHeading;
		}

		@Override
		public float ownerEffectiveGroundSpeed() {
			return ownerSpeed;
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
		public float companionX() {
			return companionX;
		}

		@Override
		public float companionY() {
			return companionY;
		}

		@Override
		public float companionZ() {
			return companionZ;
		}

		@Override
		public float companionNativeGroundSpeed() {
			return companionSpeed;
		}

		@Override
		public boolean supportedGroundMovement() {
			return groundMovement;
		}

		@Override
		public boolean companionMovementAllowed() {
			return movementAllowed;
		}

		@Override
		public double distanceToOwner() {
			return distance;
		}
	}

	private static final class TestGateway implements PlayerActionGateway {

		private final List<MovementRejectionReason> checkRejections = new ArrayList<>();
		private final List<Float> attemptedX = new ArrayList<>();
		private final List<MovementIntent> intents = new ArrayList<>();
		private final List<Float> effectiveSpeeds = new ArrayList<>();
		private MovementRejectionReason defaultCheckRejection = MovementRejectionReason.NONE;
		private MovementRejectionReason ownerOffsetRejection = MovementRejectionReason.NONE;
		private MovementRejectionReason breadcrumbRejection = MovementRejectionReason.NONE;
		private MovementRejectionReason startRejection = MovementRejectionReason.NONE;
		private MovementPhase startRejectionPhase = MovementPhase.START_EXECUTION;
		private int checkCount;
		private int startCount;
		private int stopCount;
		private int clearSpeedCount;

		@Override
		public MovementResult checkMovement(MovementIntent intent, float x, float y, float z) {
			intents.add(intent);
			attemptedX.add(x);
			MovementRejectionReason intentRejection = intent == MovementIntent.OWNER_OFFSET ? ownerOffsetRejection : breadcrumbRejection;
			MovementRejectionReason reason = intentRejection != MovementRejectionReason.NONE ? intentRejection
				: checkCount < checkRejections.size() ? checkRejections.get(checkCount) : defaultCheckRejection;
			checkCount++;
			return reason == MovementRejectionReason.NONE
				? MovementResult.success(MovementPhase.INITIAL_CHECK, x, y, z, x, y, z + 1)
				: MovementResult.rejected(MovementPhase.INITIAL_CHECK, reason, x, y, z, x, y, z + 1);
		}

		@Override
		public MovementResult startMove(MovementIntent intent, MovementResult movement, float effectiveMovementSpeed) {
			effectiveSpeeds.add(effectiveMovementSpeed);
			startCount++;
			return startRejection == MovementRejectionReason.NONE
				? MovementResult.success(MovementPhase.START_EXECUTION, movement.attemptedX(), movement.attemptedY(), movement.attemptedZ(),
					movement.resolvedX(), movement.resolvedY(), movement.resolvedZ())
				: MovementResult.rejected(startRejectionPhase, startRejection, movement.attemptedX(), movement.attemptedY(), movement.attemptedZ(),
					movement.resolvedX(), movement.resolvedY(), movement.resolvedZ());
		}

		@Override
		public void stopMove() {
			stopCount++;
		}

		@Override
		public void clearServerControlledMovementSpeed() {
			clearSpeedCount++;
		}

		private void rejectChecks(MovementRejectionReason... reasons) {
			checkRejections.addAll(Arrays.asList(reasons));
		}
	}
}
