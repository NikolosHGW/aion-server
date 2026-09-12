package com.aionemu.gameserver.services.ai;

import com.aionemu.gameserver.configs.main.AIConfig;
import com.aionemu.gameserver.configs.main.GeoDataConfig;
import com.aionemu.gameserver.geoEngine.collision.IgnoreProperties;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.taskmanager.tasks.PlayerMoveTaskManager;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.world.World;
import com.aionemu.gameserver.world.geo.GeoService;

public final class DefaultPlayerActionGateway implements PlayerActionGateway {

	private final Player player;
	private final Player visibilityTarget;
	private final boolean followLocomotionOverrideAllowed;

	public DefaultPlayerActionGateway(Player player, Player visibilityTarget) {
		this(player, visibilityTarget, false);
	}

	public DefaultPlayerActionGateway(Player player, Player visibilityTarget, boolean followLocomotionOverrideAllowed) {
		this.player = player;
		this.visibilityTarget = visibilityTarget;
		this.followLocomotionOverrideAllowed = followLocomotionOverrideAllowed;
	}

	@Override
	public MovementResult checkMovement(MovementIntent intent, float x, float y, float z) {
		return checkMovement(intent, x, y, z, MovementPhase.INITIAL_CHECK);
	}

	private MovementResult checkMovement(MovementIntent intent, float x, float y, float z, MovementPhase phase) {
		if (intent == null)
			return MovementResult.rejected(phase, MovementRejectionReason.DESTINATION_NOT_FINITE, x, y, z);
		if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
			return MovementResult.rejected(phase, MovementRejectionReason.DESTINATION_NOT_FINITE, x, y, z);
		if (!GeoDataConfig.GEO_ENABLE || !GeoDataConfig.CANSEE_ENABLE)
			return MovementResult.rejected(phase, MovementRejectionReason.GEODATA_OR_LOS_DISABLED, x, y, z);
		if (!player.isInWorld() || !player.isSpawned())
			return MovementResult.rejected(phase, MovementRejectionReason.COMPANION_NOT_IN_WORLD, x, y, z);
		if (!player.canPerformMove())
			return MovementResult.rejected(phase, MovementRejectionReason.MOVEMENT_NOT_ALLOWED, x, y, z);
		if (visibilityTarget.getWorldId() != player.getWorldId() || visibilityTarget.getInstanceId() != player.getInstanceId())
			return MovementResult.rejected(phase, MovementRejectionReason.VISIBILITY_TARGET_MAP_OR_INSTANCE_MISMATCH, x, y, z);

		int mapId = player.getWorldId();
		int instanceId = player.getInstanceId();
		GeoService geo = GeoService.getInstance();
		if (requiresOwnerLineOfSight(intent) && !geo.canSee(player, visibilityTarget))
			return MovementResult.rejected(phase, MovementRejectionReason.OWNER_LINE_OF_SIGHT, x, y, z);
		float geoZ = geo.getZ(mapId, x, y, z, instanceId);
		if (!Float.isFinite(geoZ))
			return MovementResult.rejected(phase, MovementRejectionReason.DESTINATION_HAS_NO_GEODATA, x, y, z);
		try {
			World.getInstance().createPosition(mapId, x, y, geoZ, player.getHeading(), instanceId);
		} catch (RuntimeException e) {
			return MovementResult.rejected(phase, MovementRejectionReason.DESTINATION_OUTSIDE_WORLD_REGION, x, y, z, x, y, geoZ);
		}

		float angle = PositionUtil.calculateAngleFrom(player.getX(), player.getY(), x, y);
		float distance = (float) PositionUtil.getDistance(player.getX(), player.getY(), player.getZ(), x, y, geoZ);
		Vector3f reachable = geo.findMovementCollision(player, angle, distance);
		double remaining = PositionUtil.getDistance(reachable.getX(), reachable.getY(), reachable.getZ(), x, y, geoZ);
		if (remaining > AIConfig.COMPANION_COLLISION_TOLERANCE)
			return MovementResult.rejected(phase, MovementRejectionReason.COLLISION, x, y, z, x, y, geoZ);
		if (!geo.canSee(player, x, y, geoZ, IgnoreProperties.ANY_RACE))
			return MovementResult.rejected(phase, MovementRejectionReason.LINE_OF_SIGHT, x, y, z, x, y, geoZ);
		return MovementResult.success(phase, x, y, z, x, y, geoZ);
	}

	@Override
	public MovementResult startMove(MovementIntent intent, MovementResult movement, float effectiveMovementSpeed) {
		if (movement == null)
			return MovementResult.rejected(MovementPhase.START_EXECUTION, MovementRejectionReason.DESTINATION_NOT_FINITE, Float.NaN, Float.NaN,
				Float.NaN);
		if (!movement.success())
			return movement;
		MovementResult current = checkMovement(intent, movement.resolvedX(), movement.resolvedY(), movement.resolvedZ(),
			MovementPhase.START_REVALIDATION);
		if (!current.success()) {
			float resolvedZ = Float.isFinite(current.resolvedZ()) ? current.resolvedZ() : movement.resolvedZ();
			return MovementResult.rejected(MovementPhase.START_REVALIDATION, current.reason(), movement.attemptedX(), movement.attemptedY(),
				movement.attemptedZ(), movement.resolvedX(), movement.resolvedY(), resolvedZ);
		}
		byte heading = PositionUtil.getHeadingTowards(player, current.resolvedX(), current.resolvedY());
		if (Float.isFinite(effectiveMovementSpeed)) {
			if (!followLocomotionOverrideAllowed || !player.getMoveController().setServerControlledMovementSpeed(effectiveMovementSpeed))
				return MovementResult.rejected(MovementPhase.START_EXECUTION, MovementRejectionReason.LOCOMOTION_OVERRIDE_REJECTED,
					movement.attemptedX(), movement.attemptedY(), movement.attemptedZ(), current.resolvedX(), current.resolvedY(),
					current.resolvedZ());
		} else {
			player.getMoveController().clearServerControlledMovementSpeed();
		}
		if (!player.getMoveController().startServerControlledMove(current.resolvedX(), current.resolvedY(), current.resolvedZ(), heading)) {
			player.getMoveController().clearServerControlledMovementSpeed();
			return MovementResult.rejected(MovementPhase.START_EXECUTION, MovementRejectionReason.MOVE_CONTROLLER_REJECTED,
				movement.attemptedX(), movement.attemptedY(), movement.attemptedZ(), current.resolvedX(), current.resolvedY(), current.resolvedZ());
		}
		return MovementResult.success(MovementPhase.START_EXECUTION, movement.attemptedX(), movement.attemptedY(), movement.attemptedZ(),
			current.resolvedX(), current.resolvedY(), current.resolvedZ());
	}

	@Override
	public void stopMove() {
		if (player.getMoveController().isInMove() || PlayerMoveTaskManager.getInstance().contains(player))
			player.getMoveController().stopServerControlledMove();
		else
			player.getMoveController().clearServerControlledMovementSpeed();
	}

	@Override
	public void clearServerControlledMovementSpeed() {
		player.getMoveController().clearServerControlledMovementSpeed();
	}

	static boolean requiresOwnerLineOfSight(MovementIntent intent) {
		return intent == MovementIntent.OWNER_OFFSET;
	}
}
