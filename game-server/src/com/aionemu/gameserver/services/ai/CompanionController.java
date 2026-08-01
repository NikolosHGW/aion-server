package com.aionemu.gameserver.services.ai;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

import com.aionemu.gameserver.utils.PositionUtil;

public final class CompanionController implements ServerPlayerController {

	private final CompanionContext context;
	private final PlayerActionGateway gateway;
	private final CompanionFollowSettings settings;
	private final Consumer<String> removalRequest;
	private final LongSupplier currentTimeMillis;
	private final Runnable runtimeMaintenance;
	private CompanionMode mode = CompanionMode.FOLLOWING;
	private boolean followRequested = true;
	private boolean movementIssued;
	private long nextDestinationUpdateAt;
	private long nextBlockedRetryAt;
	private String lastBlockedReason = "";

	CompanionController(CompanionContext context, PlayerActionGateway gateway, CompanionFollowSettings settings, Consumer<String> removalRequest,
			LongSupplier currentTimeMillis) {
		this(context, gateway, settings, removalRequest, currentTimeMillis, () -> {
		});
	}

	CompanionController(CompanionContext context, PlayerActionGateway gateway, CompanionFollowSettings settings, Consumer<String> removalRequest,
			LongSupplier currentTimeMillis, Runnable runtimeMaintenance) {
		this.context = context;
		this.gateway = gateway;
		this.settings = settings;
		this.removalRequest = removalRequest;
		this.currentTimeMillis = currentTimeMillis;
		this.runtimeMaintenance = runtimeMaintenance;
	}

	@Override
	public void tick() {
		runtimeMaintenance.run();
		String removalReason = getRemovalReason();
		if (removalReason != null) {
			removalRequest.accept(removalReason);
			return;
		}
		tickMovement();
	}

	private synchronized void tickMovement() {
		if (mode == CompanionMode.REMOVING)
			return;
		if (context.ownerDead()) {
			block("owner-dead", currentTimeMillis.getAsLong());
			return;
		}
		if (!followRequested) {
			mode = CompanionMode.STAYING;
			return;
		}

		long now = currentTimeMillis.getAsLong();
		if (mode == CompanionMode.BLOCKED && now < nextBlockedRetryAt)
			return;

		double distance = context.distanceToOwner();
		if (!Double.isFinite(distance)) {
			block("distance-not-finite", now);
			return;
		}
		if (distance <= settings.stopDistance()) {
			gateway.stopMove();
			movementIssued = false;
			mode = CompanionMode.FOLLOWING;
			return;
		}
		if (!movementIssued && distance <= settings.startDistance()) {
			mode = CompanionMode.FOLLOWING;
			return;
		}
		if (now < nextDestinationUpdateAt)
			return;

		double behindAngle = Math.toRadians(PositionUtil.convertHeadingToAngle(context.ownerHeading()) + 180);
		float targetX = context.ownerX() + (float) Math.cos(behindAngle) * settings.offsetDistance();
		float targetY = context.ownerY() + (float) Math.sin(behindAngle) * settings.offsetDistance();
		PlayerActionGateway.MovementCheck movement = gateway.checkMovement(targetX, targetY, context.ownerZ());
		if (!movement.allowed()) {
			block(movement.reason(), now);
			return;
		}
		if (!gateway.startMove(movement)) {
			block("gateway-start-rejected", now);
			return;
		}
		movementIssued = true;
		mode = CompanionMode.FOLLOWING;
		nextDestinationUpdateAt = now + settings.destinationUpdateIntervalMs();
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
	}

	public synchronized void stay() {
		if (mode == CompanionMode.REMOVING)
			return;
		followRequested = false;
		gateway.stopMove();
		movementIssued = false;
		mode = CompanionMode.STAYING;
	}

	@Override
	public synchronized void stop() {
		gateway.stopMove();
		movementIssued = false;
	}

	public synchronized void beginRemoval() {
		mode = CompanionMode.REMOVING;
		followRequested = false;
		stop();
	}

	public synchronized CompanionMode getMode() {
		return mode;
	}

	public synchronized String getLastBlockedReason() {
		return lastBlockedReason;
	}

	public int getOwnerObjectId() {
		return context.ownerObjectId();
	}

	public String getOwnerName() {
		return context.ownerName();
	}

	private void block(String reason, long now) {
		gateway.stopMove();
		movementIssued = false;
		mode = CompanionMode.BLOCKED;
		lastBlockedReason = reason;
		nextBlockedRetryAt = now + settings.blockedRetryIntervalMs();
	}
}
