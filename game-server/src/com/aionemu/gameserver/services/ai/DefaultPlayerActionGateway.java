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

	public DefaultPlayerActionGateway(Player player, Player visibilityTarget) {
		this.player = player;
		this.visibilityTarget = visibilityTarget;
	}

	@Override
	public MovementCheck checkMovement(float x, float y, float z) {
		if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
			return MovementCheck.blocked("destination-not-finite");
		if (!GeoDataConfig.GEO_ENABLE || !GeoDataConfig.CANSEE_ENABLE)
			return MovementCheck.blocked("geodata-or-los-disabled");
		if (!player.isInWorld() || !player.isSpawned())
			return MovementCheck.blocked("companion-not-in-world");
		if (!player.canPerformMove())
			return MovementCheck.blocked("movement-not-allowed");
		if (visibilityTarget.getWorldId() != player.getWorldId() || visibilityTarget.getInstanceId() != player.getInstanceId())
			return MovementCheck.blocked("visibility-target-map-or-instance-mismatch");

		int mapId = player.getWorldId();
		int instanceId = player.getInstanceId();
		GeoService geo = GeoService.getInstance();
		if (!geo.canSee(player, visibilityTarget))
			return MovementCheck.blocked("owner-line-of-sight");
		float geoZ = geo.getZ(mapId, x, y, z, instanceId);
		if (!Float.isFinite(geoZ))
			return MovementCheck.blocked("destination-has-no-geodata");
		try {
			World.getInstance().createPosition(mapId, x, y, geoZ, player.getHeading(), instanceId);
		} catch (RuntimeException e) {
			return MovementCheck.blocked("destination-outside-world-region");
		}

		float angle = PositionUtil.calculateAngleFrom(player.getX(), player.getY(), x, y);
		float distance = (float) PositionUtil.getDistance(player.getX(), player.getY(), player.getZ(), x, y, geoZ);
		Vector3f reachable = geo.findMovementCollision(player, angle, distance);
		double remaining = PositionUtil.getDistance(reachable.getX(), reachable.getY(), reachable.getZ(), x, y, geoZ);
		if (remaining > AIConfig.COMPANION_COLLISION_TOLERANCE)
			return MovementCheck.blocked("collision");
		if (!geo.canSee(player, x, y, geoZ, IgnoreProperties.ANY_RACE))
			return MovementCheck.blocked("line-of-sight");
		return MovementCheck.allowed(x, y, geoZ);
	}

	@Override
	public boolean startMove(MovementCheck movement) {
		if (movement == null || !movement.allowed())
			return false;
		MovementCheck current = checkMovement(movement.x(), movement.y(), movement.z());
		if (!current.allowed())
			return false;
		byte heading = PositionUtil.getHeadingTowards(player, current.x(), current.y());
		return player.getMoveController().startServerControlledMove(current.x(), current.y(), current.z(), heading);
	}

	@Override
	public void stopMove() {
		if (player.getMoveController().isInMove() || PlayerMoveTaskManager.getInstance().contains(player))
			player.getMoveController().stopServerControlledMove();
	}
}
