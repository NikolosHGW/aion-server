package com.aionemu.gameserver.services.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class CompanionStage2ASourceBoundaryTest {

	@Test
	void gatewayContainsOnlyMovementOperations() {
		Set<String> methods = List.of(PlayerActionGateway.class.getDeclaredMethods()).stream().map(method -> method.getName()).collect(Collectors.toSet());
		assertEquals(Set.of("checkMovement", "startMove", "stopMove", "clearServerControlledMovementSpeed"), methods);
		assertEquals(PlayerActionGateway.MovementResult.class,
			List.of(PlayerActionGateway.class.getDeclaredMethods()).stream().filter(method -> method.getName().equals("checkMovement")).findFirst().orElseThrow()
				.getReturnType());
		assertEquals(PlayerActionGateway.MovementResult.class,
			List.of(PlayerActionGateway.class.getDeclaredMethods()).stream().filter(method -> method.getName().equals("startMove")).findFirst().orElseThrow()
				.getReturnType());
	}

	@Test
	void companionControllerDoesNotAccessWorldMovementControllerOrPacketsDirectly() throws IOException {
		String source = Files.readString(Path.of("src/com/aionemu/gameserver/services/ai/CompanionController.java"));
		assertFalse(source.contains("PlayerMoveController"));
		assertFalse(source.contains("PlayerMoveTaskManager"));
		assertFalse(source.contains("World."));
		assertFalse(source.contains("GeoService"));
		assertFalse(source.contains("Packet"));
		assertTrue(source.contains("gateway.checkMovement"));
		assertTrue(source.contains("gateway.startMove"));
		assertTrue(source.contains("gateway.stopMove"));
	}

	@Test
	void companionLifecycleDoesNotCallPersistenceLoginLogoutOrPacketHandlers() throws IOException {
		String source = Files.readString(Path.of("src/com/aionemu/gameserver/services/ai/CompanionService.java"));
		assertFalse(source.contains("PlayerEnterWorldService"));
		assertFalse(source.contains("PlayerLeaveWorldService"));
		assertFalse(source.contains("PlayerService.storePlayer"));
		assertFalse(source.contains("PlayerDAO."));
		assertFalse(source.contains("setOnline(true)"));
		assertFalse(source.contains("network.aion.clientpackets"));
		assertFalse(source.contains("new AionConnection"));
		assertFalse(source.contains(".addTask(TaskId.PLAYER_UPDATE"));
		assertFalse(source.contains(".addTask(TaskId.INVENTORY_UPDATE"));
		assertTrue(source.contains("PlayerService.getPlayer(objectId, account, false)"));
		assertTrue(source.contains("SpawnTransaction.execute"));
		assertTrue(source.contains("verifyAbsent(newSession)"));
	}

	@Test
	void companionFeatureIsIndependentFromSyntheticPlayerFeature() throws IOException {
		String source = Files.readString(Path.of("src/com/aionemu/gameserver/services/ai/CompanionService.java"));
		assertTrue(source.contains("AIConfig.ENABLED && AIConfig.COMPANIONS_ENABLED"));
		assertFalse(source.contains("AIConfig.SYNTHETIC_PLAYERS_ENABLED"));

		String properties = Files.readString(Path.of("config/main/ai.properties"));
		assertTrue(properties.contains("ai.enabled = false"));
		assertTrue(properties.contains("ai.companions.enabled = false"));
	}

	@Test
	void statusContainsAllRequiredStage2ADiagnostics() throws IOException {
		String source = Files.readString(Path.of("src/com/aionemu/gameserver/services/ai/CompanionService.java"));
		for (String field : List.of("active=", "ownerObjectId=", "ownerName=", "companionObjectId=", "templateDbName=", "runtimeName=", "lifecycle=",
			"mode=", "clientConnectionNull=", "worldPresent=", "playerContainerPresent=", "spawned=", "schedulerRegistered=",
			"movementRegistered=", "periodicSaveTasks=", "ownerMapId=", "ownerInstanceId=", "companionMapId=", "companionInstanceId=",
			"distanceToOwner=", "followStartDistance=", "followStopDistance=", "lastBlockedReason=", "lastRemovalReason=")) {
			assertTrue(source.contains(field), "Missing status field: " + field);
		}
		assertTrue(source.contains("getMovementDiagnostics()"));
		String controllerSource = Files.readString(Path.of("src/com/aionemu/gameserver/services/ai/CompanionController.java"));
		for (String field : List.of("currentMovementRejection=", "currentMovementAttemptedDestination=", "currentMovementResolvedDestination=",
			"lastMovementRejection=", "lastMovementRejectionAttemptedDestination=", "lastMovementRejectionResolvedDestination=",
			"lastAttemptedDestination=", "lastResolvedDestination=", "consecutiveMovementRetries=", "movementBlockedSince=",
			"movementLastAttempt=", "movementLastRecovery=", "movementRecoveryCount=", "movementLastRecoveryCandidate=", "followStrategy=",
			"breadcrumbAnchored=", "breadcrumbCount=", "breadcrumbNext=", "breadcrumbDiscontinuity=", "breadcrumbConsumedCount=",
			"breadcrumbShortcutSkippedCount=", "catchUpActive=",
			"nativeMovementSpeed=", "ownerMovementSpeed=", "effectiveFollowSpeed=", "catchUpSuppression="))
			assertTrue(controllerSource.contains(field), "Missing movement diagnostic: " + field);
	}

	@Test
	void followLocomotionOverrideIsTransientServerControlledAndDoesNotMutateStats() throws IOException {
		String movement = Files.readString(Path.of("src/com/aionemu/gameserver/controllers/movement/PlayerMoveController.java"));
		assertTrue(movement.contains("owner.getClientConnection() != null"));
		assertTrue(movement.contains("serverControlledMovementSpeed"));
		assertTrue(movement.contains("EmotionType.CHANGE_SPEED"));
		assertTrue(movement.contains("new SM_EMOTION(owner, EmotionType.CHANGE_SPEED, owner.getGameStats().getMovementSpeedFloat())"));
		assertFalse(movement.contains("getGameStats().addEffect"));
		assertFalse(movement.contains("getGameStats().endEffect"));
		assertFalse(movement.contains("setMovementSpeed"));

		String combat = Files.readString(Path.of("src/com/aionemu/gameserver/services/ai/combat/DefaultCombatActionGateway.java"));
		assertFalse(combat.contains("serverControlledMovementSpeed"));
		assertFalse(combat.contains("effectiveFollowSpeed"));

		String scheduler = Files.readString(Path.of("src/com/aionemu/gameserver/services/ai/SyntheticPlayerScheduler.java"));
		assertFalse(scheduler.contains("Breadcrumb"));
		assertFalse(scheduler.contains("catchUp"));
	}

	@Test
	void ownerLogoutAndShutdownHaveExplicitCleanupHooks() throws IOException {
		String leaveWorld = Files.readString(Path.of("src/com/aionemu/gameserver/services/player/PlayerLeaveWorldService.java"));
		String shutdown = Files.readString(Path.of("src/com/aionemu/gameserver/ShutdownHook.java"));
		assertTrue(leaveWorld.contains("CompanionService.getInstance().ownerLeaving(player"));
		assertTrue(shutdown.contains("CompanionService.getInstance().shutdown()"));
		assertTrue(shutdown.contains("SyntheticPlayerRuntime.getInstance().shutdownScheduler()"));
	}
}
