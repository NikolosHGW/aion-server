package com.aionemu.gameserver.services.ai;

import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.taskmanager.tasks.PlayerMoveTaskManager;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.world.World;

final class RouteServerPlayerController implements ServerPlayerController {

	private final Player player;
	private final RouteController routeController;
	private final RouteController.RouteActor routeActor = new PlayerRouteActor();
	private ServerControlledPlayer controlledPlayer;

	RouteServerPlayerController(Player player, RouteController routeController) {
		this.player = player;
		this.routeController = routeController;
	}

	void bind(ServerControlledPlayer controlledPlayer) {
		if (this.controlledPlayer != null)
			throw new IllegalStateException("Route controller is already bound");
		this.controlledPlayer = controlledPlayer;
	}

	@Override
	public void tick() {
		routeController.tick(routeActor);
	}

	@Override
	public void stop() {
		routeController.stop(routeActor);
	}

	int getCurrentWaypointIndex() {
		return routeController.getCurrentWaypointIndex();
	}

	RoutePoint getCurrentWaypoint() {
		return routeController.getCurrentWaypoint();
	}

	private final class PlayerRouteActor implements RouteController.RouteActor {

		@Override
		public boolean isEligible() {
			return controlledPlayer != null && controlledPlayer.isActive()
				&& World.getInstance().findVisibleObject(player.getObjectId()) == player && player.isInWorld() && player.isSpawned();
		}

		@Override
		public boolean isMoving() {
			return player.getMoveController().isInMove() && PlayerMoveTaskManager.getInstance().contains(player);
		}

		@Override
		public double distanceTo(RoutePoint point) {
			return PositionUtil.getDistance(player.getX(), player.getY(), player.getZ(), point.x(), point.y(), point.z());
		}

		@Override
		public void start(RoutePoint point) {
			byte heading = PositionUtil.getHeadingTowards(player, point.x(), point.y());
			if (!player.getMoveController().startServerControlledMove(point.x(), point.y(), point.z(), heading))
				throw new IllegalStateException("Unable to start server-controlled movement for " + player);
		}

		@Override
		public void stop() {
			if (player.getMoveController().isInMove() || PlayerMoveTaskManager.getInstance().contains(player))
				player.getMoveController().stopServerControlledMove();
		}
	}
}
