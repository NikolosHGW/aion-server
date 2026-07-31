package com.aionemu.gameserver.services.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class StageOneSourceBoundaryTest {

	@Test
	void stageOneDoesNotCallPersistenceLoginLogoutOrPacketHandlers() throws IOException {
		String source = readAiSources();

		assertFalse(source.contains("PlayerEnterWorldService."));
		assertFalse(source.contains("PlayerLeaveWorldService."));
		assertFalse(source.contains("PlayerService.storePlayer("));
		assertFalse(source.contains("PlayerDAO.onlinePlayer("));
		assertFalse(source.contains(".setOnline(true)"));
		assertFalse(source.contains(".addTask(TaskId.PLAYER_UPDATE"));
		assertFalse(source.contains(".addTask(TaskId.INVENTORY_UPDATE"));
		assertFalse(source.contains("network.aion.clientpackets"));
		assertTrue(source.contains("PlayerService.getPlayer(objectId, account, false)"));
	}

	@Test
	void runtimeTemplateChangesCannotInvokeDaoSaveOrCreateFakeConnection() throws IOException {
		String source = readAiSources();
		assertFalse(source.contains("DAO."));
		assertFalse(source.contains("new AionConnection"));
		assertFalse(source.contains("setClientConnection"));
		assertFalse(source.contains("storePlayer"));
	}

	@Test
	void featureFlagsDefaultToFalse() throws IOException {
		String properties = Files.readString(Path.of("config/main/ai.properties"));
		assertTrue(properties.contains("ai.enabled = false"));
		assertTrue(properties.contains("ai.synthetic_players.enabled = false"));
		assertTrue(properties.contains("ai.companions.enabled = false"));
		assertTrue(properties.contains("ai.citizens.enabled = false"));
		assertTrue(properties.contains("ai.economy.enabled = false"));
	}

	@Test
	void serviceChecksThatPeriodicSaveTasksAreAbsent() throws IOException {
		String source = Files.readString(Path.of("src/com/aionemu/gameserver/services/ai/ServerControlledPlayerService.java"));
		assertTrue(source.contains("hasTask(TaskId.PLAYER_UPDATE)"));
		assertTrue(source.contains("hasTask(TaskId.INVENTORY_UPDATE)"));
		assertTrue(source.contains("assertNoPeriodicSaveTasks"));
	}

	@Test
	void syntheticPlayerSelfHooksAreGuardedFromQuestAndZoneProcessing() throws IOException {
		String source = Files.readString(Path.of("src/com/aionemu/gameserver/controllers/PlayerController.java"));
		assertTrue(source.contains("ServerControlledPlayerService.getInstance().isServerControlled(getOwner())"));
		assertTrue(source.contains("ServerControlledPlayerService.getInstance().isServerControlled(player)"));
	}

	@Test
	void routeAndMovementUseGeoHeadingAndExistingSpeedInterpolation() throws IOException {
		String service = Files.readString(Path.of("src/com/aionemu/gameserver/services/ai/ServerControlledPlayerService.java"));
		String playerMovement = Files.readString(Path.of("src/com/aionemu/gameserver/controllers/movement/PlayerMoveController.java"));
		String playableMovement = Files.readString(Path.of("src/com/aionemu/gameserver/controllers/movement/PlayableMoveController.java"));
		assertTrue(service.contains("world.createPosition"));
		assertTrue(service.contains("geo.findMovementCollision"));
		assertTrue(service.contains("geo.canSee"));
		assertTrue(playerMovement.contains("World.getInstance().updatePosition(owner"));
		assertTrue(playerMovement.contains("MovementMask.NPC_STARTMOVE"));
		assertTrue(playableMovement.contains("getMovementSpeedFloat()"));
	}

	@Test
	void readOnlyLoadModeDoesNotInitializeMissingRows() throws IOException {
		String passports = Files.readString(Path.of("src/com/aionemu/gameserver/dao/AccountPassportsDAO.java"));
		String lifeStats = Files.readString(Path.of("src/com/aionemu/gameserver/dao/PlayerLifeStatsDAO.java"));
		assertTrue(passports.contains("else if (initializeMissingPersistentData)"));
		assertTrue(lifeStats.contains("else if (initializeMissingPersistentData)"));
	}

	@Test
	void cleanupStopsSchedulingAndMovementBeforeRemovingTheWorldObject() throws IOException {
		String source = Files.readString(Path.of("src/com/aionemu/gameserver/services/ai/ServerControlledPlayerService.java"));
		int schedulerCleanup = source.indexOf("scheduler.unregister(controlled)");
		int movementCleanup = source.indexOf("controlled::stopMovement");
		int worldCleanup = source.indexOf("World.getInstance().removeObject(player)");

		assertTrue(schedulerCleanup >= 0);
		assertTrue(movementCleanup > schedulerCleanup);
		assertTrue(worldCleanup > movementCleanup);
		assertTrue(source.contains("verifyAbsent(finalControlledPlayer)"));
		assertTrue(source.contains("verifyAbsent(player)"));
	}

	@Test
	void statusContainsAllRequiredLifecycleDiagnostics() throws IOException {
		String source = Files.readString(Path.of("src/com/aionemu/gameserver/services/ai/ServerControlledPlayerService.java"));
		for (String field : List.of("objectId=", "templateDbName=", "runtimeName=", "lifecycle=", "clientConnectionNull=", "worldPresent=",
			"spawned=", "schedulerRegistered=", "movementRegistered=", "mapId=", "instanceId=", "waypointIndex=")) {
			assertTrue(source.contains(field), "Missing status field: " + field);
		}
	}

	private String read(Path path) {
		try {
			return Files.readString(path);
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private String readAiSources() throws IOException {
		Path sourceDirectory = Path.of("src/com/aionemu/gameserver/services/ai");
		try (Stream<Path> files = Files.walk(sourceDirectory)) {
			return files.filter(path -> path.toString().endsWith(".java")).map(this::read).reduce("", String::concat);
		}
	}
}
