package com.aionemu.gameserver.services.ai;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.configs.main.AIConfig;
import com.aionemu.gameserver.configs.main.GeoDataConfig;
import com.aionemu.gameserver.geoEngine.collision.IgnoreProperties;
import com.aionemu.gameserver.geoEngine.math.Vector3f;
import com.aionemu.gameserver.model.TaskId;
import com.aionemu.gameserver.model.account.Account;
import com.aionemu.gameserver.model.account.PlayerAccountData;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.AccountService;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.taskmanager.tasks.PlayerMoveTaskManager;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.world.World;
import com.aionemu.gameserver.world.WorldPosition;
import com.aionemu.gameserver.world.geo.GeoService;

public final class ServerControlledPlayerService {

	private static final Logger log = LoggerFactory.getLogger(ServerControlledPlayerService.class);

	private final SyntheticPlayerRuntime runtime = SyntheticPlayerRuntime.getInstance();
	private final ServerControlledPlayerRegistry registry = runtime.getRegistry();
	private final SyntheticPlayerScheduler scheduler = runtime.getScheduler();

	private ServerControlledPlayerService() {
	}

	public static ServerControlledPlayerService getInstance() {
		return SingletonHolder.INSTANCE;
	}

	public synchronized ServerControlledPlayer spawn(Player admin) {
		requireEnabled();
		if (registry.getActive(SyntheticPlayerRole.ROUTE_SPIKE) != null)
			throw new IllegalStateException("Stage 1 already has an active synthetic player");
		if (AIConfig.SYNTHETIC_TEMPLATE_ACCOUNT_ID <= 0 || AIConfig.SYNTHETIC_TEMPLATE_CHARACTER_ID <= 0)
			throw new IllegalStateException("Template account and character IDs must be configured");
		if (!GeoDataConfig.GEO_ENABLE || !GeoDataConfig.CANSEE_ENABLE)
			throw new IllegalStateException("Stage 1 route validation requires geodata and can-see checks");

		int objectId = AIConfig.SYNTHETIC_TEMPLATE_CHARACTER_ID;
		Account account = AccountService.loadAccount(AIConfig.SYNTHETIC_TEMPLATE_ACCOUNT_ID);
		PlayerAccountData templateData = account.getPlayerAccountData(objectId);
		if (templateData == null)
			throw new IllegalArgumentException(
				"Character " + objectId + " does not belong to configured account " + AIConfig.SYNTHETIC_TEMPLATE_ACCOUNT_ID);

		String templateDatabaseName = templateData.getPlayerCommonData().getName();
		String runtimeName = AIConfig.SYNTHETIC_RUNTIME_NAME_PREFIX + templateDatabaseName;
		SyntheticPlayerPreflight.validate(objectId, runtimeName, AIConfig.SYNTHETIC_RUNTIME_NAME_MAX_LENGTH, occupancy(null));
		if (templateData.getPlayerCommonData().isOnline())
			throw new IllegalStateException("Template character is marked online in the database; close the account and clear the marker normally");

		Player player = null;
		ServerControlledPlayer controlledPlayer = null;
		try {
			player = PlayerService.getPlayer(objectId, account, false);
			validateLoadedPlayer(player);
			List<RoutePoint> route = buildAndValidateRoute(player, admin);
			player.getCommonData().setName(runtimeName);
			RouteServerPlayerController routeController = new RouteServerPlayerController(player,
				new RouteController(route, AIConfig.SYNTHETIC_ROUTE_ARRIVAL_TOLERANCE));
			controlledPlayer = new ServerControlledPlayer(player, templateDatabaseName, runtimeName, SyntheticPlayerRole.ROUTE_SPIKE, routeController,
				() -> AIConfig.ENABLED && AIConfig.SYNTHETIC_PLAYERS_ENABLED, this::despawn);
			routeController.bind(controlledPlayer);

			ServerControlledPlayer finalControlledPlayer = controlledPlayer;
			SpawnTransaction.execute(new SpawnTransaction.Steps() {

				@Override
				public void registerWrapper() {
					registry.register(finalControlledPlayer);
					finalControlledPlayer.markRegistered();
				}

				@Override
				public void storeWorldObject() {
					SyntheticPlayerPreflight.validateAvailable(objectId, runtimeName, occupancy(finalControlledPlayer));
					World.getInstance().storeObject(finalControlledPlayer.getPlayer());
					if (World.getInstance().findVisibleObject(objectId) != finalControlledPlayer.getPlayer()
						|| World.getInstance().getPlayer(objectId) != finalControlledPlayer.getPlayer()
						|| World.getInstance().getPlayer(runtimeName) != finalControlledPlayer.getPlayer())
						throw new IllegalStateException("World/PlayerContainer registration postcondition failed");
				}

				@Override
				public void spawnWorldObject() {
					World.getInstance().spawn(finalControlledPlayer.getPlayer());
					finalControlledPlayer.activate();
				}

				@Override
				public void registerScheduler() {
					assertNoPeriodicSaveTasks(finalControlledPlayer.getPlayer());
					scheduler.register(finalControlledPlayer);
				}

				@Override
				public void rollback() {
					removeRuntimePlayer(finalControlledPlayer, "spawn-rollback");
				}

				@Override
				public void verifyRolledBack() {
					verifyAbsent(finalControlledPlayer);
				}
			});

			log.info(
				"SYNTHETIC_PLAYER lifecycle=ACTIVE objectId={} templateDbName={} runtimeName={} connectionNull={} mapId={} instanceId={} waypoint={}",
				objectId, templateDatabaseName, runtimeName, player.getClientConnection() == null, player.getWorldId(), player.getInstanceId(),
				controlledPlayer.getCurrentWaypointIndex());
			return controlledPlayer;
		} catch (RuntimeException | Error e) {
			if (controlledPlayer == null && player != null)
				releaseRuntimeOnlyReferences(player, templateDatabaseName);
			throw e;
		}
	}

	public synchronized boolean despawn(String reason) {
		ServerControlledPlayer player = registry.getActive(SyntheticPlayerRole.ROUTE_SPIKE);
		if (player == null)
			return false;
		RuntimeException cleanupFailure = null;
		try {
			removeRuntimePlayer(player, reason);
		} catch (RuntimeException e) {
			cleanupFailure = e;
		}
		try {
			verifyAbsent(player);
		} catch (RuntimeException e) {
			if (cleanupFailure == null)
				cleanupFailure = e;
			else
				cleanupFailure.addSuppressed(e);
		}
		if (cleanupFailure != null)
			throw cleanupFailure;
		return true;
	}

	public synchronized void shutdown() {
		despawn("shutdown");
	}

	public boolean isServerControlled(Player player) {
		return runtime.isServerControlled(player);
	}

	public String getStatus() {
		ServerControlledPlayer controlled = registry.getActive(SyntheticPlayerRole.ROUTE_SPIKE);
		if (controlled == null) {
			return "active=false, flags=" + AIConfig.ENABLED + "/" + AIConfig.SYNTHETIC_PLAYERS_ENABLED;
		}
		Player player = controlled.getPlayer();
		RoutePoint waypoint = controlled.getCurrentWaypoint();
		boolean worldPresent = World.getInstance().findVisibleObject(player.getObjectId()) == player;
		boolean playerContainerPresent = World.getInstance().getPlayer(player.getObjectId()) == player
			&& World.getInstance().getPlayer(controlled.getRuntimeName()) == player;
		return "active=true"
			+ ", objectId=" + player.getObjectId()
			+ ", templateDbName=" + controlled.getTemplateDatabaseName()
			+ ", runtimeName=" + controlled.getRuntimeName()
			+ ", lifecycle=" + controlled.getState()
			+ ", clientConnectionNull=" + (player.getClientConnection() == null)
			+ ", worldPresent=" + worldPresent
			+ ", playerContainerPresent=" + playerContainerPresent
			+ ", spawned=" + player.isSpawned()
			+ ", schedulerRegistered=" + scheduler.contains(controlled)
			+ ", movementRegistered=" + PlayerMoveTaskManager.getInstance().contains(player)
			+ ", periodicSaveTasks=" + hasPeriodicSaveTasks(player)
			+ ", mapId=" + player.getWorldId()
			+ ", instanceId=" + player.getInstanceId()
			+ ", waypointIndex=" + controlled.getCurrentWaypointIndex()
			+ ", waypoint=(" + waypoint.x() + "," + waypoint.y() + "," + waypoint.z() + ")";
	}

	private void requireEnabled() {
		SyntheticPlayerPreflight.requireEnabled(AIConfig.ENABLED, AIConfig.SYNTHETIC_PLAYERS_ENABLED);
	}

	private void validateLoadedPlayer(Player player) {
		if (player.getClientConnection() != null)
			throw new IllegalStateException("Template unexpectedly has an AionConnection");
		if (player.getPlayerAppearance() == null || player.getKnownList() == null || player.getPlayerSettings() == null || player.getAbyssRank() == null
			|| player.getMotions() == null || player.getGameStats() == null || player.getEquipment() == null || player.getPosition() == null)
			throw new IllegalStateException("Template player is not fully initialized for player packet presentation");
		assertNoPeriodicSaveTasks(player);
	}

	private List<RoutePoint> buildAndValidateRoute(Player player, Player admin) {
		List<RouteController.Offset> offsets = RouteController.parseOffsets(AIConfig.SYNTHETIC_ROUTE_OFFSETS);
		List<RoutePoint> route = new ArrayList<>(offsets.size());
		World world = World.getInstance();
		GeoService geo = GeoService.getInstance();
		int mapId = admin.getWorldId();
		int instanceId = admin.getInstanceId();

		for (RouteController.Offset offset : offsets) {
			float x = admin.getX() + offset.x();
			float y = admin.getY() + offset.y();
			float z = geo.getZ(mapId, x, y, admin.getZ(), instanceId);
			if (!Float.isFinite(z))
				throw new IllegalArgumentException("No geodata surface for route point " + x + "," + y);
			route.add(new RoutePoint(x, y, z));
		}
		RoutePreflight.validate(route, AIConfig.SYNTHETIC_ROUTE_MAX_SEGMENT_LENGTH, new RoutePreflight.Geometry() {

			@Override
			public boolean isValidPoint(RoutePoint point) {
				try {
					world.createPosition(mapId, point.x(), point.y(), point.z(), (byte) 0, instanceId);
					return true;
				} catch (RuntimeException e) {
					return false;
				}
			}

			@Override
			public boolean isSegmentClear(RoutePoint from, RoutePoint to) {
				player.setPosition(world.createPosition(mapId, from.x(), from.y(), from.z(),
					PositionUtil.getHeadingTowards(from.x(), from.y(), to.x(), to.y()), instanceId));
				float angle = PositionUtil.calculateAngleFrom(from.x(), from.y(), to.x(), to.y());
				float distance = (float) PositionUtil.getDistance(from.x(), from.y(), from.z(), to.x(), to.y(), to.z());
				Vector3f reachable = geo.findMovementCollision(player, angle, distance);
				double remaining = PositionUtil.getDistance(reachable.getX(), reachable.getY(), reachable.getZ(), to.x(), to.y(), to.z());
				return remaining <= AIConfig.SYNTHETIC_ROUTE_COLLISION_TOLERANCE;
			}

			@Override
			public boolean hasLineOfSight(RoutePoint from, RoutePoint to) {
				player.setPosition(world.createPosition(mapId, from.x(), from.y(), from.z(),
					PositionUtil.getHeadingTowards(from.x(), from.y(), to.x(), to.y()), instanceId));
				return geo.canSee(player, to.x(), to.y(), to.z(), IgnoreProperties.ANY_RACE);
			}
		});

		RoutePoint start = route.get(0);
		RoutePoint next = route.get(1);
		WorldPosition startPosition = world.createPosition(mapId, start.x(), start.y(), start.z(),
			PositionUtil.getHeadingTowards(start.x(), start.y(), next.x(), next.y()), instanceId);
		player.setPosition(startPosition);
		return List.copyOf(route);
	}

	private void removeRuntimePlayer(ServerControlledPlayer controlled, String reason) {
		Player player = controlled.getPlayer();
		controlled.beginRemoval();
		List<RuntimeException> failures = new ArrayList<>();
		attemptCleanup("scheduler", () -> scheduler.unregister(controlled), failures);
		attemptCleanup("movement", controlled::stopMovement, failures);
		attemptCleanup("target", () -> player.setTarget(null), failures);
		attemptCleanup("world", () -> {
			if (World.getInstance().findVisibleObject(player.getObjectId()) == player)
				World.getInstance().removeObject(player);
		}, failures);
		if (World.getInstance().findVisibleObject(player.getObjectId()) != player) {
			attemptCleanup("registry", () -> registry.unregister(controlled), failures);
			controlled.markRemoved();
			attemptCleanup("runtime-references", () -> releaseRuntimeOnlyReferences(player, controlled.getTemplateDatabaseName()), failures);
		}
		log.info(
			"SYNTHETIC_PLAYER lifecycle={} reason={} objectId={} templateDbName={} runtimeName={} connectionNull={} worldPresent={} schedulerRegistered={} movementRegistered={}",
			controlled.getState(), reason, player.getObjectId(), controlled.getTemplateDatabaseName(), controlled.getRuntimeName(),
			player.getClientConnection() == null, World.getInstance().findVisibleObject(player.getObjectId()) == player, scheduler.contains(controlled),
			PlayerMoveTaskManager.getInstance().contains(player));
		if (!failures.isEmpty()) {
			IllegalStateException failure = new IllegalStateException("Synthetic player cleanup failed in " + failures.size() + " subsystem(s)");
			failures.forEach(failure::addSuppressed);
			throw failure;
		}
	}

	private void attemptCleanup(String subsystem, Runnable cleanup, List<RuntimeException> failures) {
		try {
			cleanup.run();
		} catch (RuntimeException e) {
			log.error("Synthetic player cleanup failed in {}", subsystem, e);
			failures.add(e);
		}
	}

	private void verifyAbsent(ServerControlledPlayer controlled) {
		Player player = controlled.getPlayer();
		List<String> retainedBy = new ArrayList<>();
		if (World.getInstance().findVisibleObject(player.getObjectId()) == player)
			retainedBy.add("World");
		if (World.getInstance().getPlayer(player.getObjectId()) == player || World.getInstance().getPlayer(controlled.getRuntimeName()) == player)
			retainedBy.add("PlayerContainer");
		if (registry.contains(controlled))
			retainedBy.add("registry");
		if (scheduler.contains(controlled))
			retainedBy.add("scheduler");
		if (PlayerMoveTaskManager.getInstance().contains(player))
			retainedBy.add("movementManager");
		if (!retainedBy.isEmpty())
			throw new IllegalStateException("Synthetic player cleanup incomplete; retained by " + retainedBy);
	}

	private void releaseRuntimeOnlyReferences(Player player, String templateDatabaseName) {
		player.getCommonData().setName(templateDatabaseName);
		player.getInventory().setOwner(null);
		player.getWarehouse().setOwner(null);
		player.getAccount().getAccountWarehouse().setOwner(null);
	}

	private void assertNoPeriodicSaveTasks(Player player) {
		if (hasPeriodicSaveTasks(player))
			throw new IllegalStateException("Synthetic player must not receive periodic save tasks");
	}

	private boolean hasPeriodicSaveTasks(Player player) {
		return player.getController().hasTask(TaskId.PLAYER_UPDATE) || player.getController().hasTask(TaskId.INVENTORY_UPDATE);
	}

	private SyntheticPlayerPreflight.Occupancy occupancy(ServerControlledPlayer expectedRegistryEntry) {
		return new SyntheticPlayerPreflight.Occupancy() {

			@Override
			public Object worldObjectById(int objectId) {
				return World.getInstance().findVisibleObject(objectId);
			}

			@Override
			public Object playerById(int objectId) {
				return World.getInstance().getPlayer(objectId);
			}

			@Override
			public Object playerByName(String name) {
				Player exact = World.getInstance().getPlayer(name);
				if (exact != null)
					return exact;
				return World.getInstance().getAllPlayers().stream().filter(p -> p.getName().equalsIgnoreCase(name)).findFirst().orElse(null);
			}

			@Override
			public boolean registryContains(int objectId) {
				return registry.contains(objectId)
					&& (expectedRegistryEntry == null || expectedRegistryEntry.getObjectId() != objectId || !registry.contains(expectedRegistryEntry));
			}

			@Override
			public boolean schedulerContains(int objectId) {
				return scheduler.contains(objectId);
			}

			@Override
			public boolean movementContains(int objectId) {
				return PlayerMoveTaskManager.getInstance().containsObjectId(objectId);
			}
		};
	}

	private static final class SingletonHolder {

		private static final ServerControlledPlayerService INSTANCE = new ServerControlledPlayerService();
	}
}
