package com.aionemu.gameserver.services.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class CompanionStage2ESourceBoundaryTest {

	private static final Path SOURCE = Path.of("src/com/aionemu/gameserver");

	@Test
	void persistenceFlagIsDefaultOffAndLocalConfigRequiresExplicitDdlEnablement() throws IOException {
		String base = Files.readString(Path.of("config/main/ai.properties"));
		String local = Files.readString(Path.of("../docker/server-config/game/mygs.properties"));
		assertTrue(base.contains("ai.companions.goal_persistence.enabled = false"));
		assertTrue(local.contains("ai.companions.goal_persistence.enabled = false"));
	}

	@Test
	void schemaSeparatesBindingFromGoalAndContainsNoShadowProgress() throws IOException {
		String fresh = Files.readString(Path.of("sql/aion_gs.sql"));
		String update = Files.readString(Path.of("sql/update.sql"));
		String focusedMigration = Files.readString(Path.of("sql/ai_companion_persistence.sql"));
		for (String schema : List.of(fresh, update, focusedMigration)) {
			assertTrue(schema.contains("ai_companions"));
			assertTrue(schema.contains("ai_companion_goals"));
			assertTrue(schema.contains("semantic_fingerprint"));
			for (String forbidden : List.of("execution_state", "quest_status", "quest_progress", "current_progress", "reward"))
				assertFalse(aiTableDefinitions(schema).contains(forbidden), forbidden);
		}
	}

	@Test
	void choosePersistsBeforeRuntimeChoiceAndClearDeletesBeforeRuntimeClear() throws IOException {
		String service = read("services/ai/CompanionService.java");
		int persist = service.indexOf("persistChosenGoal(current, candidate.plan())");
		int choose = service.indexOf("current.goalSession().choose(requester.getObjectId(), controlled.getObjectId(), candidate.plan())", persist);
		int delete = service.indexOf("clearPersistedGoal(current)");
		int clear = service.indexOf("current.goalSession().clear()", delete);
		assertTrue(persist > 0 && persist < choose);
		assertTrue(delete > 0 && delete < clear);
	}

	@Test
	void removalNeverDeletesPersistenceAndRestoreReadsNativeQuestOnlyThroughAcceptedTracker() throws IOException {
		String service = read("services/ai/CompanionService.java");
		String removal = service.substring(service.indexOf("private void removeRuntimeCompanion"), service.indexOf("private void attemptCleanup"));
		assertFalse(removal.contains("persistenceRepository"));
		assertFalse(removal.contains("CompanionPersistenceDAO"));
		for (String forbidden : List.of("setQuestVar", "setStatus(", "PlayerQuestListDAO.store", "QuestService.startQuest",
			"QuestService.finishQuest", "giveReward(", "addExp(", "InventoryDAO", "PlayerService.storePlayer"))
			assertFalse(service.contains(forbidden), forbidden);
	}

	@Test
	void persistenceLayerHasNoSchedulerConnectionPacketOrTemplateSaveDependency() throws IOException {
		String sources = read("services/ai/persistence/CompanionPersistenceRepository.java")
			+ read("services/ai/persistence/CompanionGoalRestorePolicy.java")
			+ read("services/ai/persistence/QuestGoalSemanticFingerprint.java")
			+ read("dao/CompanionPersistenceDAO.java");
		for (String forbidden : List.of("AionConnection", "CM_", "SM_", "ThreadPoolManager", "ScheduledExecutor", "new Thread", "schedule(",
			"PlayerQuestListDAO", "PlayerService", "InventoryDAO"))
			assertFalse(sources.contains(forbidden), forbidden);
	}

	private static String aiTableDefinitions(String schema) {
		int start = schema.indexOf("ai_companions");
		int end = schema.indexOf("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", schema.indexOf("ai_companion_goals", start));
		return schema.substring(start, end);
	}

	private static String read(String relativePath) throws IOException {
		return Files.readString(SOURCE.resolve(relativePath));
	}
}
