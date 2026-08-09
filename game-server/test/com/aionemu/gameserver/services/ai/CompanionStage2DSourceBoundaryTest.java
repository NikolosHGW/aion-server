package com.aionemu.gameserver.services.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class CompanionStage2DSourceBoundaryTest {

	private static final Path SOURCE = Path.of("src/com/aionemu/gameserver");

	@Test
	void executionFlagsAreDefaultOffWithEmptyAllowlistAndSafePolling() throws IOException {
		String config = Files.readString(Path.of("config/main/ai.properties"));
		assertTrue(config.contains("ai.companions.quest_execution.enabled = false"));
		assertTrue(config.contains("ai.companions.quest_execution.combat_enabled = false"));
		assertTrue(config.contains("ai.companions.quest_execution.allowed_quest_ids =\n"));
		assertTrue(config.contains("ai.companions.quest_execution.poll_interval_ms = 1000"));
	}

	@Test
	void trackerHasNoQuestMutationProtocolPersistenceOrSchedulerBoundary() throws IOException {
		String sources = read(List.of(
			"services/ai/quest/CompanionQuestExecutionTracker.java",
			"services/ai/quest/CompanionQuestExecutionRuntime.java",
			"services/ai/quest/QuestNativeSnapshot.java",
			"services/ai/quest/QuestExecutionSnapshot.java",
			"services/ai/quest/QuestExecutionSession.java"));
		for (String forbidden : List.of("QuestService.startQuest", "QuestService.finishQuest", "QuestService.abandonQuest",
			"QuestEngine.getInstance().onDialog", "QuestEngine.getInstance().onKill", "setQuestVar", "setStatus(", "addQuest(", "deleteQuest(",
			"SM_QUEST", "PlayerQuestListDAO", "PlayerService.storePlayer", "ItemService", "Inventory", "AionConnection", "CM_",
			"ThreadPoolManager", "ScheduledExecutor", "new Thread", "schedule(", "Future"))
			assertFalse(sources.contains(forbidden), forbidden);
		assertTrue(sources.contains("state.getQuestVarById(0)"));
		assertTrue(sources.contains("state.getStatus()"));
	}

	@Test
	void serviceReadsExactOwnerQuestAndUsesOnlyExistingCompanionTick() throws IOException {
		String service = read("services/ai/CompanionService.java");
		String controller = read("services/ai/CompanionController.java");
		assertTrue(service.contains("owner.getQuestStateList().getQuestState(executionPlan.questId())"));
		assertTrue(service.contains("executionRuntime.pollIfDue()"));
		assertTrue(controller.contains("runtimeMaintenance.run()"));
		for (String forbidden : List.of("new SyntheticPlayerScheduler", "ThreadPoolManager", "new Thread", "ScheduledExecutor", "Future"))
			assertFalse(service.contains(forbidden), forbidden);
	}

	@Test
	void goalClearAndEveryRemovalPathDropOnlyRuntimeExecution() throws IOException {
		String service = read("services/ai/CompanionService.java");
		assertTrue(service.contains("current.executionRuntime().clear(\"admin-command\")"));
		assertTrue(service.contains("current.executionRuntime().clear(reason)"));
		assertTrue(service.contains("current.executionRuntime().clear(\"quest-execution-disabled\")"));
		assertTrue(service.contains("retainedBy.add(\"questExecutionRuntime\")"));
		for (String forbidden : List.of("QuestService.abandonQuest", "QuestService.finishQuest", "PlayerService.storePlayer"))
			assertFalse(service.contains(forbidden), forbidden);
	}

	@Test
	void reattachAndAlreadyCompleteNeverAcceptOrRewardTheQuest() throws IOException {
		String service = read("services/ai/CompanionService.java");
		String formatter = read("services/ai/quest/QuestGoalCommandFormatter.java");
		assertTrue(service.contains("QuestStatus.START"));
		assertTrue(service.contains("QuestStatus.REWARD"));
		assertTrue(service.contains("QuestStatus.COMPLETE"));
		assertTrue(service.contains("QuestGoalProposalResult.alreadyComplete"));
		assertTrue(formatter.contains("ALREADY_COMPLETE"));
		for (String forbidden : List.of("startQuest", "finishQuest", "sendQuest", "SM_QUEST"))
			assertFalse(service.contains(forbidden), forbidden);
	}

	@Test
	void questPolicyRechecksLiveStateImmediatelyBeforeTheSingleCheckedHit() throws IOException {
		String gateway = read("services/ai/combat/DefaultCombatActionGateway.java");
		String policy = read("services/ai/combat/QuestCombatAuthorizationPolicy.java");
		int recheck = gateway.indexOf("questAuthorizationPolicy.recheckLiveState(signal, target)");
		int checkedHit = gateway.indexOf("companion.getController().attackTarget(target, 0, false)");
		assertTrue(recheck > 0 && recheck < checkedHit);
		assertEquals(1, count(gateway, "companion.getController().attackTarget(target, 0, false)"));
		assertTrue(policy.contains("owner.getQuestStateList().getQuestState(view.session().questId())"));
		assertTrue(policy.contains("nativeState.getStatus() != QuestStatus.START"));
		assertTrue(policy.contains("int progress = nativeState.getQuestVarById(0)"));
		assertTrue(policy.contains("progress >= manifest.requiredProgress()"));
		for (String forbidden : List.of("QuestEngine", "setQuestVar", "setStatus(", "reduceHp(", "SM_QUEST", "CM_ATTACK", "AionConnection"))
			assertFalse(policy.contains(forbidden), forbidden);
	}

	@Test
	void questAuthorizationIsExactAndLegacyNpcRemainsIndependent() throws IOException {
		String policy = read("services/ai/combat/QuestCombatAuthorizationPolicy.java");
		String gateway = read("services/ai/combat/DefaultCombatActionGateway.java");
		for (String required : List.of("PERSONAL_COMPANION", "FIRST_EXECUTION_QUEST_ID", "ACTIVE_OBJECTIVE", "matchesTarget",
			"manifest.mapId()", "NpcObjectType.NORMAL", "NpcRating.NORMAL", "getKnownList().knows", "getKnownList().sees",
			"PositionUtil.isInAttackRange", "GeoService.getInstance().canSee"))
			assertTrue(policy.contains(required), required);
		assertTrue(gateway.contains("TargetAuthorization.LEGACY"));
		assertTrue(gateway.contains("CombatNpcAllowlist.FIRST_SPIKE_NPC_ID"));
		assertTrue(gateway.contains("isApprovedFirstSpikeSpawn(target)"));
	}

	@Test
	void immutableManifestContainsAuditedTargetsAndRejectsWalkers() throws IOException {
		String manifest = read("services/ai/quest/QuestExecutionManifest.java");
		String fingerprint = read("services/ai/quest/QuestSpawnFingerprint.java");
		assertTrue(manifest.contains("TARGET_1_ID = 210133"));
		assertTrue(manifest.contains("TARGET_2_ID = 210134"));
		assertTrue(manifest.contains("REQUIRED_PROGRESS = 3"));
		assertTrue(manifest.contains("approvedTargetSpawns.size() != 39"));
		assertTrue(fingerprint.contains("spawn.getWalkerId() == null"));
		assertTrue(fingerprint.contains("!spawn.isTemporarySpawn()"));
		assertTrue(fingerprint.contains("!spawn.isEventSpawn()"));
	}

	@Test
	void nativeRewardAndCreditPipelineHasNoSecondAiHook() throws IOException {
		String damageList = read("controllers/attack/DamageList.java");
		String npcController = read("controllers/NpcController.java");
		String teamReward = read("model/team/common/service/PlayerTeamDistributionService.java");
		String aiSources = read(List.of("services/ai/combat/QuestCombatAuthorizationPolicy.java",
			"services/ai/quest/CompanionQuestExecutionTracker.java", "services/ai/CompanionService.java"));
		assertTrue(damageList.contains("CombatContributionOwnerResolver.getInstance().resolve"));
		assertEquals(1, count(npcController, "QuestEngine.getInstance().onKill"));
		assertEquals(1, count(teamReward, "QuestEngine.getInstance().onKill"));
		assertFalse(aiSources.contains("QuestEngine.getInstance().onKill"));
		for (String forbidden : List.of("giveReward(", "addExp(", "registerDrop(", "setQuestVar"))
			assertFalse(aiSources.contains(forbidden), forbidden);
	}

	@Test
	void centralQuestCoreAndPlayerHaveNoStage2DDependency() throws IOException {
		for (String path : List.of("model/gameobjects/player/Player.java", "services/QuestService.java",
			"model/gameobjects/player/QuestStateList.java", "questEngine/model/QuestState.java", "questEngine/QuestEngine.java",
			"questEngine/handlers/template/MonsterHunt.java", "dao/PlayerQuestListDAO.java"))
			assertFalse(read(path).contains("QuestExecution"), path);
	}

	private static String read(String relativePath) throws IOException {
		return Files.readString(SOURCE.resolve(relativePath));
	}

	private static String read(List<String> paths) throws IOException {
		StringBuilder result = new StringBuilder();
		for (String path : paths)
			result.append(read(path));
		return result.toString();
	}

	private static int count(String source, String token) {
		int count = 0;
		for (int index = 0; (index = source.indexOf(token, index)) >= 0; index += token.length())
			count++;
		return count;
	}
}
