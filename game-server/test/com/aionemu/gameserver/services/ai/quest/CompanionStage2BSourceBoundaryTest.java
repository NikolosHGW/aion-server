package com.aionemu.gameserver.services.ai.quest;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class CompanionStage2BSourceBoundaryTest {

	@Test
	void configurationIsDefaultOffWithAnEmptyAllowlist() throws IOException {
		String properties = Files.readString(Path.of("config/main/ai.properties"));
		assertTrue(properties.contains("ai.companions.quest_goals.enabled = false"));
		assertTrue(properties.contains("ai.companions.quest_goals.allowed_quest_ids =\n"));
	}

	@Test
	void plannerPackageCannotInvokeQuestMutationPacketsPersistenceOrRandomSelection() throws IOException {
		String sources = readSources(Path.of("src/com/aionemu/gameserver/services/ai/quest"));
		for (String forbidden : List.of("QuestEngine.getInstance().onDialog", "QuestService.startQuest", "QuestService.finishQuest",
			"QuestService.addOrUpdateQuest", "QuestStateList.addQuest", "QuestStateList.deleteQuest", "PlayerQuestListDAO", "ItemService",
			"network.aion.clientpackets", "new AionConnection", "com.aionemu.gameserver.utils.Rnd"))
			assertFalse(sources.contains(forbidden), "Forbidden Stage 2B boundary: " + forbidden);
	}

	@Test
	void playerActionGatewayWasNotExtendedAndGoalSessionHasNoScheduler() throws IOException {
		String gateway = Files.readString(Path.of("src/com/aionemu/gameserver/services/ai/PlayerActionGateway.java"));
		String session = Files.readString(Path.of("src/com/aionemu/gameserver/services/ai/quest/CompanionGoalSession.java"));
		String planner = Files.readString(Path.of("src/com/aionemu/gameserver/services/ai/quest/QuestGoalPlanner.java"));
		assertFalse(gateway.contains("Quest"));
		assertFalse(session.contains("Scheduler"));
		assertFalse(session.contains("tick("));
		assertFalse(planner.contains("gameobjects.player.Player"));
	}

	@Test
	void commandUsesChooseNeverSelectAndStatesThatQuestWasNotAccepted() throws IOException {
		String command = Files.readString(Path.of("data/handlers/admincommands/Companion.java"));
		String formatter = Files.readString(Path.of("src/com/aionemu/gameserver/services/ai/quest/QuestGoalCommandFormatter.java"));
		String service = Files.readString(Path.of("src/com/aionemu/gameserver/services/ai/CompanionService.java"));
		String session = Files.readString(Path.of("src/com/aionemu/gameserver/services/ai/quest/CompanionGoalSession.java"));
		assertTrue(command.contains("case \"today\""));
		assertTrue(command.contains("case \"choose\""));
		assertFalse(command.contains("case \"select\""));
		assertTrue(command.contains("formatChoice(service.chooseGoal(admin))"));
		assertTrue(formatter.contains("runtime AI goal only; quest not accepted"));
		assertTrue(service.contains("allowedIds.contains(offered.questId())"));
		assertTrue(service.contains("goalCandidateProvider.provide(requester, offered.questId())"));
		assertTrue(service.contains("goalEligibility.check(requester, offered.questId())"));
		assertTrue(service.contains("? \"STALE\" : result.status()"));
		assertFalse(session.contains("throw new IllegalStateException"));
	}

	@Test
	void goalSessionIsClearedByAllExistingCompanionRemovalPathsAndRuntimeDisableMaintenance() throws IOException {
		String service = Files.readString(Path.of("src/com/aionemu/gameserver/services/ai/CompanionService.java"));
		assertTrue(service.contains("current.goalSession().clear()"));
		assertTrue(service.contains("maintainGoalSession();"));
		assertTrue(service.contains("combatController.tick();"));
		assertTrue(service.contains("ownerLeaving"));
		assertTrue(service.contains("shutdown()"));
		assertTrue(service.contains("removeRuntimeCompanion"));
	}

	@Test
	void quest1102FixtureRemainsTheCuratedMonsterHunt() throws IOException {
		String questData = Files.readString(Path.of("data/static_data/quest_data/quest_data.xml"));
		String scriptData = Files.readString(Path.of("data/static_data/quest_script_data/poeta.xml"));
		String spawns = Files.readString(Path.of("data/static_data/spawns/Npcs/210010000_Poeta.xml"));
		assertTrue(questData.contains("id=\"1102\" name=\"Kerubar Hunt\""));
		assertTrue(questData.contains("count=\"3\" npc_ids=\"210133 210134\""));
		assertTrue(scriptData.contains("<monster_hunt id=\"1102\" start_npc_ids=\"203057\""));
		assertTrue(spawns.contains("npc_id=\"203057\""));
		assertTrue(spawns.contains("npc_id=\"210133\""));
		assertTrue(spawns.contains("npc_id=\"210134\""));
	}

	private String readSources(Path directory) throws IOException {
		StringBuilder result = new StringBuilder();
		try (var paths = Files.list(directory)) {
			for (Path path : paths.filter(file -> file.toString().endsWith(".java")).sorted().toList())
				result.append(Files.readString(path));
		}
		return result.toString();
	}
}
