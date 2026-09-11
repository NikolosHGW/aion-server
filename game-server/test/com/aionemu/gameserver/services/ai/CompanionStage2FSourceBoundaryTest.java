package com.aionemu.gameserver.services.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class CompanionStage2FSourceBoundaryTest {

	private static final Path SOURCE = Path.of("src/com/aionemu/gameserver");

	@Test
	void creationIsDefaultOffAndUsesExplicitClosedHostAccount() throws IOException {
		String base = Files.readString(Path.of("config/main/ai.properties"));
		String local = Files.readString(Path.of("../docker/server-config/game/mygs.properties"));
		assertTrue(base.contains("ai.companions.creation.enabled = false"));
		assertTrue(base.contains("ai.companions.creation.host_account_id = 0"));
		assertTrue(local.contains("ai.companions.creation.host_account_id = 13"));
		assertTrue(local.contains("ai.companions.creation.host_account_name = Test"));
	}

	@Test
	void productCreateUsesDomainPlayerCreationWithoutClientSlotOrLoginAccountMutation() throws IOException {
		String gateway = read("services/ai/creation/DefaultCompanionPlayerCreationGateway.java");
		assertTrue(gateway.contains("PlayerService.newPlayer"));
		assertTrue(gateway.contains("PlayerService.storeNewPlayer"));
		assertTrue(gateway.contains("setLevel(1)"));
		assertTrue(gateway.contains("CompanionCreationPolicy.STARTING_CLASS"));
		for (String forbidden : List.of("CM_CREATE_CHARACTER", "AionConnection", "CHARACTER_LIMIT_COUNT", "CHARACTER_ADDITIONAL_COUNT",
			"account_data", "LoginServer", "setExp(", "addExp(", "giveItem", "addKinah"))
			assertFalse(gateway.contains(forbidden), forbidden);
	}

	@Test
	void summonResolvesProductBodyThroughClaimAndBindingNotTemplateCharacter() throws IOException {
		String service = read("services/ai/CompanionService.java");
		int branch = service.indexOf("if (AIConfig.COMPANION_CREATION_ENABLED)");
		int legacy = service.indexOf("} else {", branch);
		String productBranch = service.substring(branch, legacy);
		assertTrue(productBranch.contains("creationService.resolveOwnedBody"));
		assertFalse(productBranch.contains("COMPANION_TEMPLATE_CHARACTER_ID"));
		assertTrue(read("services/ai/creation/CompanionCreationService.java")
			.contains("No persistent companion; use companion create first"));
	}

	@Test
	void creationClaimAndBindingAreUniqueAndDismissNeverDeletesEither() throws IOException {
		for (String schema : List.of(Files.readString(Path.of("sql/aion_gs.sql")), Files.readString(Path.of("sql/update.sql")),
			Files.readString(Path.of("sql/ai_companion_creation.sql")))) {
			assertTrue(schema.contains("ai_companion_creation_claims"));
			assertTrue(schema.contains("PRIMARY KEY (`owner_player_id`)"));
			assertTrue(schema.contains("ai_companion_creation_claims_companion_unique"));
			assertTrue(schema.contains("ON DELETE RESTRICT"));
		}
		String service = read("services/ai/CompanionService.java");
		String removal = service.substring(service.indexOf("private void removeRuntimeCompanion"), service.indexOf("private void attemptCleanup"));
		assertFalse(removal.contains("delete"));
		assertFalse(removal.contains("persistenceRepository"));
	}

	@Test
	void commandExposesExplicitCreateAndCreationPathHasNoRuntimeScheduler() throws IOException {
		String command = Files.readString(Path.of("data/handlers/admincommands/Companion.java"));
		assertTrue(command.contains("case \"create\""));
		assertTrue(command.contains("Use //companion summon"));
		String creation = read("services/ai/creation/CompanionCreationService.java")
			+ read("services/ai/creation/DefaultCompanionPlayerCreationGateway.java");
		for (String forbidden : List.of("ThreadPoolManager", "ScheduledExecutor", "new Thread", "schedule(", "World.storeObject", "World.spawn"))
			assertFalse(creation.contains(forbidden), forbidden);
	}

	private static String read(String relativePath) throws IOException {
		return Files.readString(SOURCE.resolve(relativePath));
	}
}
