package com.aionemu.gameserver.services.ai;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import com.aionemu.gameserver.services.ai.CompanionFollowDestinationPolicy.Candidate;
import com.aionemu.gameserver.services.ai.CompanionFollowDestinationPolicy.CandidateKind;
import com.aionemu.gameserver.services.ai.PlayerActionGateway.MovementPhase;
import com.aionemu.gameserver.services.ai.PlayerActionGateway.MovementIntent;
import com.aionemu.gameserver.services.ai.PlayerActionGateway.MovementRejectionReason;
import com.aionemu.gameserver.services.ai.PlayerActionGateway.MovementResult;
import com.aionemu.gameserver.utils.PositionUtil;

public final class CompanionController implements ServerPlayerController {
	private static final int MAX_BREADCRUMB_CANDIDATES = 3;

	private final CompanionContext context;
	private final PlayerActionGateway gateway;
	private final CompanionFollowSettings settings;
	private final Consumer<String> removalRequest;
	private final LongSupplier currentTimeMillis;
	private final Runnable runtimeMaintenance;
	private final BooleanSupplier combatCatchUpSuppressed;
	private final CompanionOwnerBreadcrumbTrail ownerTrail = new CompanionOwnerBreadcrumbTrail();
	private CompanionMode mode = CompanionMode.FOLLOWING;
	private boolean followRequested = true;
	private boolean movementIssued;
	private long nextDestinationUpdateAt;
	private long nextBlockedRetryAt;
	private MovementResult currentRejection;
	private MovementResult lastRejection;
	private MovementResult lastAttempt;
	private int consecutiveRetryCount;
	private long blockedSince = -1;
	private long lastAttemptAt = -1;
	private long lastRecoveryAt = -1;
	private long recoveryCount;
	private String lastRecoveryCandidate = "";
	private boolean localRecoveryActive;
	private MovementIntent followStrategy = MovementIntent.OWNER_OFFSET;
	private String breadcrumbNext = "NONE";
	private boolean catchUpActive;
	private float nativeMovementSpeed = Float.NaN;
	private float ownerMovementSpeed = Float.NaN;
	private float effectiveFollowSpeed = Float.NaN;
	private String catchUpSuppression = "NONE";

	CompanionController(CompanionContext context, PlayerActionGateway gateway, CompanionFollowSettings settings, Consumer<String> removalRequest,
			LongSupplier currentTimeMillis) {
		this(context, gateway, settings, removalRequest, currentTimeMillis, () -> {
		}, () -> false);
	}

	CompanionController(CompanionContext context, PlayerActionGateway gateway, CompanionFollowSettings settings, Consumer<String> removalRequest,
			LongSupplier currentTimeMillis, Runnable runtimeMaintenance) {
		this(context, gateway, settings, removalRequest, currentTimeMillis, runtimeMaintenance, () -> false);
	}

	CompanionController(CompanionContext context, PlayerActionGateway gateway, CompanionFollowSettings settings, Consumer<String> removalRequest,
			LongSupplier currentTimeMillis, Runnable runtimeMaintenance, BooleanSupplier combatCatchUpSuppressed) {
		this.context = context;
		this.gateway = gateway;
		this.settings = settings;
		this.removalRequest = removalRequest;
		this.currentTimeMillis = currentTimeMillis;
		this.runtimeMaintenance = runtimeMaintenance;
		this.combatCatchUpSuppressed = combatCatchUpSuppressed;
	}

	@Override
	public void tick() {
		runtimeMaintenance.run();
		String removalReason = getRemovalReason();
		if (removalReason != null) {
			clearFollowRuntime(removalReason);
			removalRequest.accept(removalReason);
			return;
		}
		tickMovement();
	}

	private synchronized void tickMovement() {
		if (mode == CompanionMode.REMOVING)
			return;
		long now = currentTimeMillis.getAsLong();
		if (context.ownerDead()) {
			if (mode != CompanionMode.BLOCKED || now >= nextBlockedRetryAt)
				block(policyRejection(MovementRejectionReason.OWNER_DEAD), now);
			return;
		}
		if (!followRequested) {
			mode = CompanionMode.STAYING;
			return;
		}
		ownerTrail.sample(context.ownerMapId(), context.ownerInstanceId(), context.ownerX(), context.ownerY(), context.ownerZ(),
			context.ownerHeading(), context.ownerEffectiveGroundSpeed(), now);
		double distance = context.distanceToOwner();
		if (Double.isFinite(distance) && distance <= settings.startDistance() && ownerTrail.size() > 0)
			ownerTrail.anchorAtCurrentOwner();
		CompanionOwnerBreadcrumbTrail.Breadcrumb nextBreadcrumb = ownerTrail.next(context.companionX(), context.companionY(), context.companionZ(),
			settings.stopDistance(), now);
		List<CompanionOwnerBreadcrumbTrail.Breadcrumb> breadcrumbCandidates = ownerTrail.lookAhead(MAX_BREADCRUMB_CANDIDATES);
		breadcrumbNext = describe(nextBreadcrumb);

		if (mode == CompanionMode.BLOCKED && now < nextBlockedRetryAt) {
			clearLocomotion("BLOCKED");
			return;
		}

		if (!Double.isFinite(distance)) {
			block(policyRejection(MovementRejectionReason.DISTANCE_NOT_FINITE), now);
			return;
		}
		if (distance <= settings.stopDistance()) {
			gateway.stopMove();
			movementIssued = false;
			followStrategy = MovementIntent.OWNER_OFFSET;
			clearLocomotion("NORMAL_RANGE");
			recover(now, "WITHIN_STOP_DISTANCE");
			return;
		}
		if (!movementIssued && distance <= settings.startDistance()) {
			followStrategy = MovementIntent.OWNER_OFFSET;
			clearLocomotion("NORMAL_RANGE");
			recover(now, "WITHIN_START_DISTANCE");
			return;
		}
		refreshLocomotionState(distance);
		if (now < nextDestinationUpdateAt)
			return;

		if (distance >= CompanionFollowLocomotionPolicy.SIGNIFICANT_SEPARATION && nextBreadcrumb != null) {
			if (attemptBreadcrumb(breadcrumbCandidates, distance, now))
				return;
			return;
		}

		MovementResult finalRejection = null;
		for (Candidate candidate : CompanionFollowDestinationPolicy.candidates(context.ownerX(), context.ownerY(), context.ownerZ(),
			PositionUtil.convertHeadingToAngle(context.ownerHeading()), settings.offsetDistance())) {
			followStrategy = MovementIntent.OWNER_OFFSET;
			MovementResult movement = gateway.checkMovement(MovementIntent.OWNER_OFFSET, candidate.x(), candidate.y(), candidate.z());
			recordAttempt(movement, now);
			if (!movement.success()) {
				recordRejection(movement);
				finalRejection = movement;
				if (CompanionFollowDestinationPolicy.allowsAlternateCandidate(movement.reason()))
					continue;
				if (allowsBreadcrumbRecovery(movement.reason(), distance, nextBreadcrumb)) {
					attemptBreadcrumb(breadcrumbCandidates, distance, now);
					return;
				}
				block(movement, now);
				return;
			}

			CompanionFollowLocomotionPolicy.Decision locomotion = locomotionDecision(distance);
			MovementResult started = gateway.startMove(MovementIntent.OWNER_OFFSET, movement,
				locomotion.requiresOverride(nativeMovementSpeed) ? locomotion.effectiveSpeed() : Float.NaN);
			recordAttempt(started, now);
			if (!started.success()) {
				recordRejection(started);
				finalRejection = started;
				if (CompanionFollowDestinationPolicy.allowsAlternateCandidate(started.reason()))
					continue;
				block(started, now);
				return;
			}

			movementIssued = true;
			followStrategy = MovementIntent.OWNER_OFFSET;
			acceptLocomotion(locomotion);
			boolean recovered = mode == CompanionMode.BLOCKED || candidate.kind() != CandidateKind.REAR;
			movementSucceeded(now, recovered ? candidate.kind().name() : "");
			nextDestinationUpdateAt = now + settings.destinationUpdateIntervalMs();
			return;
		}
		if (allowsBreadcrumbRecovery(finalRejection == null ? MovementRejectionReason.NONE : finalRejection.reason(), distance, nextBreadcrumb)) {
			attemptBreadcrumb(breadcrumbCandidates, distance, now);
			return;
		}
		block(finalRejection, now);
	}

	private boolean attemptBreadcrumb(List<CompanionOwnerBreadcrumbTrail.Breadcrumb> candidates, double distance, long now) {
		MovementResult finalRejection = null;
		for (int index = 0; index < candidates.size(); index++) {
			CompanionOwnerBreadcrumbTrail.Breadcrumb breadcrumb = candidates.get(index);
			followStrategy = MovementIntent.OWNER_BREADCRUMB;
			MovementResult movement = gateway.checkMovement(MovementIntent.OWNER_BREADCRUMB, breadcrumb.x(), breadcrumb.y(), breadcrumb.z());
			recordAttempt(movement, now);
			if (!movement.success()) {
				recordRejection(movement);
				finalRejection = movement;
				if (index + 1 < candidates.size() && CompanionFollowDestinationPolicy.allowsAlternateCandidate(movement.reason()))
					continue;
				block(movement, now);
				return false;
			}
			CompanionFollowLocomotionPolicy.Decision locomotion = locomotionDecision(distance);
			MovementResult started = gateway.startMove(MovementIntent.OWNER_BREADCRUMB, movement,
				locomotion.requiresOverride(nativeMovementSpeed) ? locomotion.effectiveSpeed() : Float.NaN);
			recordAttempt(started, now);
			if (!started.success()) {
				recordRejection(started);
				finalRejection = started;
				if (index + 1 < candidates.size() && CompanionFollowDestinationPolicy.allowsAlternateCandidate(started.reason()))
					continue;
				block(started, now);
				return false;
			}
			int skipped = ownerTrail.skipBefore(breadcrumb);
			breadcrumbNext = describe(breadcrumb);
			movementIssued = true;
			acceptLocomotion(locomotion);
			String recovery = mode == CompanionMode.BLOCKED || skipped > 0 ? "OWNER_BREADCRUMB" : "";
			movementSucceeded(now, recovery);
			nextDestinationUpdateAt = now + settings.destinationUpdateIntervalMs();
			return true;
		}
		if (finalRejection != null)
			block(finalRejection, now);
		return false;
	}

	private boolean isBreadcrumbRecovery(MovementRejectionReason reason) {
		return reason == MovementRejectionReason.OWNER_LINE_OF_SIGHT || CompanionFollowDestinationPolicy.allowsAlternateCandidate(reason);
	}

	private boolean allowsBreadcrumbRecovery(MovementRejectionReason reason, double distance,
			CompanionOwnerBreadcrumbTrail.Breadcrumb breadcrumb) {
		return breadcrumb != null && distance > settings.startDistance() && isBreadcrumbRecovery(reason);
	}

	private CompanionFollowLocomotionPolicy.Decision locomotionDecision(double distance) {
		nativeMovementSpeed = context.companionNativeGroundSpeed();
		ownerMovementSpeed = context.ownerEffectiveGroundSpeed();
		return CompanionFollowLocomotionPolicy.decide(nativeMovementSpeed, ownerMovementSpeed, distance,
			context.supportedGroundMovement(), context.companionMovementAllowed(), combatCatchUpSuppressed.getAsBoolean(), catchUpActive,
			settings.startDistance());
	}

	private void acceptLocomotion(CompanionFollowLocomotionPolicy.Decision decision) {
		effectiveFollowSpeed = decision.effectiveSpeed();
		catchUpActive = decision.catchUpActive();
		catchUpSuppression = decision.suppression();
	}

	private void refreshLocomotionState(double distance) {
		CompanionFollowLocomotionPolicy.Decision decision = locomotionDecision(distance);
		if (!decision.requiresOverride(nativeMovementSpeed))
			gateway.clearServerControlledMovementSpeed();
		acceptLocomotion(decision);
	}

	private synchronized String getRemovalReason() {
		if (mode == CompanionMode.REMOVING)
			return null;
		if (!context.ownerConnected()) {
			return "owner-disconnected";
		}
		if (!context.ownerInWorld() || !context.ownerSpawned()) {
			return "owner-left-world";
		}
		if (!context.companionInWorld() || !context.companionSpawned()) {
			return "companion-left-world";
		}
		if (context.ownerMapId() != context.companionMapId() || context.ownerInstanceId() != context.companionInstanceId()) {
			return "owner-map-or-instance-changed";
		}
		return null;
	}

	public synchronized void follow() {
		if (mode == CompanionMode.REMOVING)
			return;
		followRequested = true;
		mode = CompanionMode.FOLLOWING;
		nextDestinationUpdateAt = 0;
		nextBlockedRetryAt = 0;
		clearCurrentBlock();
		localRecoveryActive = false;
		ownerTrail.reset("MANUAL_FOLLOW");
		breadcrumbNext = "NONE";
		followStrategy = MovementIntent.OWNER_OFFSET;
		clearLocomotion("MANUAL_FOLLOW");
	}

	public synchronized void stay() {
		if (mode == CompanionMode.REMOVING)
			return;
		followRequested = false;
		gateway.stopMove();
		movementIssued = false;
		mode = CompanionMode.STAYING;
		clearCurrentBlock();
		localRecoveryActive = false;
		ownerTrail.reset("STAY");
		breadcrumbNext = "NONE";
		followStrategy = MovementIntent.OWNER_OFFSET;
		clearLocomotion("STAY");
	}

	@Override
	public synchronized void stop() {
		gateway.stopMove();
		movementIssued = false;
		clearLocomotion("STOPPED");
	}

	public synchronized void beginRemoval() {
		mode = CompanionMode.REMOVING;
		followRequested = false;
		ownerTrail.reset("REMOVING");
		breadcrumbNext = "NONE";
		stop();
	}

	public synchronized CompanionMode getMode() {
		return mode;
	}

	public synchronized String getLastBlockedReason() {
		return currentRejection == null ? "" : currentRejection.reason().name();
	}

	public synchronized String getMovementDiagnostics() {
		return "currentMovementRejection=" + describeRejection(currentRejection)
			+ ", followStrategy=" + followStrategy
			+ ", breadcrumbAnchored=" + ownerTrail.isAnchored()
			+ ", breadcrumbCount=" + ownerTrail.size()
			+ ", breadcrumbNext=" + breadcrumbNext
			+ ", breadcrumbDiscontinuity=" + ownerTrail.discontinuity()
			+ ", breadcrumbConsumedCount=" + ownerTrail.consumedCount()
			+ ", breadcrumbShortcutSkippedCount=" + ownerTrail.shortcutSkippedCount()
			+ ", catchUpActive=" + catchUpActive
			+ ", nativeMovementSpeed=" + nativeMovementSpeed
			+ ", ownerMovementSpeed=" + ownerMovementSpeed
			+ ", effectiveFollowSpeed=" + effectiveFollowSpeed
			+ ", catchUpSuppression=" + catchUpSuppression
			+ ", currentMovementAttemptedDestination=" + describeAttempted(currentRejection)
			+ ", currentMovementResolvedDestination=" + describeResolved(currentRejection)
			+ ", lastMovementRejection=" + describeRejection(lastRejection)
			+ ", lastMovementRejectionAttemptedDestination=" + describeAttempted(lastRejection)
			+ ", lastMovementRejectionResolvedDestination=" + describeResolved(lastRejection)
			+ ", lastAttemptedDestination=" + describeAttempted(lastAttempt)
			+ ", lastResolvedDestination=" + describeResolved(lastAttempt)
			+ ", consecutiveMovementRetries=" + consecutiveRetryCount
			+ ", movementBlockedSince=" + blockedSince
			+ ", movementLastAttempt=" + lastAttemptAt
			+ ", movementLastRecovery=" + lastRecoveryAt
			+ ", movementRecoveryCount=" + recoveryCount
			+ ", movementLastRecoveryCandidate=" + lastRecoveryCandidate;
	}

	public int getOwnerObjectId() {
		return context.ownerObjectId();
	}

	public String getOwnerName() {
		return context.ownerName();
	}

	private MovementResult policyRejection(MovementRejectionReason reason) {
		return MovementResult.rejected(MovementPhase.POLICY, reason, Float.NaN, Float.NaN, Float.NaN);
	}

	private void recordAttempt(MovementResult result, long now) {
		lastAttempt = result;
		lastAttemptAt = now;
	}

	private void recordRejection(MovementResult result) {
		lastRejection = result;
	}

	private void block(MovementResult rejection, long now) {
		if (rejection == null)
			throw new IllegalStateException("Blocked movement requires a rejection result");
		recordAttempt(rejection, now);
		recordRejection(rejection);
		gateway.stopMove();
		movementIssued = false;
		clearLocomotion("BLOCKED");
		if (mode != CompanionMode.BLOCKED || blockedSince < 0)
			blockedSince = now;
		mode = CompanionMode.BLOCKED;
		currentRejection = rejection;
		consecutiveRetryCount++;
		nextBlockedRetryAt = now + settings.blockedRetryIntervalMs();
		localRecoveryActive = false;
	}

	private void movementSucceeded(long now, String recoveryCandidate) {
		boolean recoveredFromBlocked = mode == CompanionMode.BLOCKED;
		boolean usingAlternate = recoveryCandidate.startsWith("REAR_");
		if (recoveredFromBlocked || usingAlternate && !localRecoveryActive) {
			lastRecoveryAt = now;
			recoveryCount++;
			lastRecoveryCandidate = recoveryCandidate;
		}
		localRecoveryActive = usingAlternate;
		mode = CompanionMode.FOLLOWING;
		clearCurrentBlock();
	}

	private void recover(long now, String recoveryCandidate) {
		movementSucceeded(now, mode == CompanionMode.BLOCKED ? recoveryCandidate : "");
	}

	private void clearCurrentBlock() {
		currentRejection = null;
		consecutiveRetryCount = 0;
		blockedSince = -1;
		nextBlockedRetryAt = 0;
	}

	private String describeRejection(MovementResult result) {
		return result == null ? "NONE" : result.phase() + ":" + result.reason();
	}

	private String describeAttempted(MovementResult result) {
		return result == null ? "NONE" : coordinates(result.attemptedX(), result.attemptedY(), result.attemptedZ());
	}

	private String describeResolved(MovementResult result) {
		return result == null || !Float.isFinite(result.resolvedZ()) ? "NONE"
			: coordinates(result.resolvedX(), result.resolvedY(), result.resolvedZ());
	}

	private String coordinates(float x, float y, float z) {
		return x + "/" + y + "/" + z;
	}

	private void clearFollowRuntime(String reason) {
		ownerTrail.reset(reason);
		breadcrumbNext = "NONE";
		followStrategy = MovementIntent.OWNER_OFFSET;
		clearLocomotion(reason);
	}

	private void clearLocomotion(String reason) {
		gateway.clearServerControlledMovementSpeed();
		catchUpActive = false;
		nativeMovementSpeed = context.companionNativeGroundSpeed();
		ownerMovementSpeed = context.ownerEffectiveGroundSpeed();
		effectiveFollowSpeed = nativeMovementSpeed;
		catchUpSuppression = reason;
	}

	private String describe(CompanionOwnerBreadcrumbTrail.Breadcrumb breadcrumb) {
		return breadcrumb == null ? "NONE" : coordinates(breadcrumb.x(), breadcrumb.y(), breadcrumb.z());
	}
}
