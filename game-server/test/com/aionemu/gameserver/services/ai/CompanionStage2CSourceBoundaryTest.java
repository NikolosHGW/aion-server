package com.aionemu.gameserver.services.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.services.ai.combat.CombatActionGateway;

class CompanionStage2CSourceBoundaryTest {

	private static final Path SOURCE = Path.of("src/com/aionemu/gameserver");

	@Test
	void combatGatewayIsSeparateFromMovementGateway() {
		Set<String> movementMethods = List.of(PlayerActionGateway.class.getDeclaredMethods()).stream().map(method -> method.getName())
			.collect(Collectors.toSet());
		Set<String> combatMethods = List.of(CombatActionGateway.class.getDeclaredMethods()).stream().map(method -> method.getName())
			.collect(Collectors.toSet());
		assertEquals(Set.of("checkMovement", "startMove", "stopMove"), movementMethods);
		assertEquals(Set.of("basicHitOnce", "stop"), combatMethods);
	}

	@Test
	void gatewayUsesCheckedSynchronousPlayerAttackWithoutForbiddenBoundaries() throws IOException {
		String source = read("services/ai/combat/DefaultCombatActionGateway.java");
		assertTrue(source.contains("companion.getController().attackTarget(target, 0, false)"));
		assertTrue(source.contains("getAttackCounter()"));
		assertTrue(source.contains("getAttackSpeed().getCurrent()"));
		for (String forbidden : List.of("CM_ATTACK", "AionConnection", "AttackUtil", "reduceHp(", "setCurrentHp(", "PacketSendUtility",
			"SkillEngine", "ThreadPoolManager", "schedule(", "InventoryDAO", "PlayerService.storePlayer"))
			assertFalse(source.contains(forbidden), forbidden);
	}

	@Test
	void companionAttackPreparesNormalPlayerPresentationThenAppliesOneImmediateDamage() throws IOException {
		String playerController = read("controllers/PlayerController.java");
		String creatureController = read("controllers/CreatureController.java");
		assertTrue(playerController.contains("activeCompanion = personalCompanion && CompanionService.getInstance().isActiveCompanion(getOwner())"));
		int heading = playerController.indexOf("setH(PositionUtil.getHeadingTowards(getOwner(), target))");
		int presentation = playerController.indexOf("CompanionCombatPresentation.prepareBasicAttack(getOwner(), target)");
		int attack = playerController.indexOf("super.attackTarget(target, 0, true)");
		assertTrue(heading >= 0 && heading < presentation && presentation < attack);
		assertTrue(playerController.contains("super.attackTarget(target, time, true)"));
		assertEquals(1, count(creatureController, "new SM_ATTACK("));
		assertEquals(1, count(creatureController, "target.getController().onAttack(getOwner(), damage, firstAttackStatus, criticalEffect)"));
		assertTrue(creatureController.contains("if (time == 0)"));
	}

	@Test
	void duplicateAndRejectedPreflightReturnBeforeAttackPresentation() throws IOException {
		String gateway = read("services/ai/combat/DefaultCombatActionGateway.java");
		int duplicateReturn = gateway.indexOf("CombatStatus.DUPLICATE_EVENT");
		int inspect = gateway.indexOf("EnumSet<CombatPreflightViolation> violations = inspect(signal)");
		int rejectedReturn = gateway.indexOf("if (status != CombatStatus.HIT_STARTED)");
		int attack = gateway.indexOf("companion.getController().attackTarget(target, 0, false)");
		assertTrue(duplicateReturn < inspect && inspect < rejectedReturn && rejectedReturn < attack);
	}

	@Test
	void normalAndCompanionPresentationUseTheSameWeaponAttackPacket() throws IOException {
		String clientPacket = read("network/aion/clientpackets/CM_ATTACK.java");
		String serverPacket = read("network/aion/serverpackets/SM_ATTACK.java");
		String packetUtility = read("utils/PacketSendUtility.java");
		assertTrue(clientPacket.contains("time = readUH()"));
		assertTrue(clientPacket.contains("player.getController().attackTarget((Creature) obj, time, false)"));
		for (String field : List.of("writeD(attacker.getObjectId())", "writeH(time)", "writeC(attackTypeAnimation.getId())",
			"writeC(attackHandAnimation.getId())", "writeD(target.getObjectId())"))
			assertTrue(serverPacket.contains(field), field);
		assertTrue(packetUtility.contains("creature.getKnownList().forEachObject"));
		assertTrue(packetUtility.contains("if (object instanceof Player player)"));
		assertTrue(packetUtility.contains("sendPacket(player, packet)"));
	}

	@Test
	void companionMirrorsNormalTargetAndWeaponModeWithoutFakeAttackEmotion() throws IOException {
		String targetInput = read("network/aion/clientpackets/CM_TARGET_SELECT.java");
		String emotionInput = read("network/aion/clientpackets/CM_EMOTION.java");
		String presentation = read("services/ai/combat/CompanionCombatPresentation.java");
		assertTrue(targetInput.contains("player.setTarget(newTarget)"));
		assertTrue(emotionInput.contains("case ATTACKMODE_IN_STANDING"));
		assertTrue(emotionInput.contains("player.setState(CreatureState.WEAPON_EQUIPPED)"));
		assertTrue(presentation.contains("new SM_TARGET_UPDATE(companion)"));
		assertTrue(presentation.contains("EmotionType.ATTACKMODE_IN_STANDING"));
		assertTrue(presentation.contains("CreatureState.WEAPON_EQUIPPED"));
		assertTrue(presentation.contains("broadcastToSightedPlayers"));
		assertTrue(presentation.contains("EmotionType.NEUTRALMODE_IN_STANDING"));
		for (String forbidden : List.of("EmotionType.ATTACK,", "AttackUtil", "onAttack(", "reduceHp(", "setCurrentHp(", "AionConnection", "CM_",
			"ThreadPoolManager", "schedule(", "Future"))
			assertFalse(presentation.contains(forbidden), forbidden);
	}

	@Test
	void presentationHasNoConnectionOrAiSchedulerDependency() throws IOException {
		String playerController = read("controllers/PlayerController.java");
		String attackMethod = playerController.substring(playerController.indexOf("public void attackTarget(Creature target"),
			playerController.indexOf("public void onAttack(Creature attacker"));
		for (String forbidden : List.of("AionConnection", "ThreadPoolManager", "schedule(", "Future", "SM_EMOTION", "getClientConnection"))
			assertFalse(attackMethod.contains(forbidden), forbidden);
	}

	@Test
	void gatewayFailClosedChecksAllApprovedCombatBoundaries() throws IOException {
		String source = read("services/ai/combat/DefaultCombatActionGateway.java");
		for (String required : List.of("featureEnabled", "assistEnabled", "contributionResolver.matches", "EVENT_EXPIRED", "instanceId",
			"TARGET_NOT_NPC", "FIRST_SPIKE_NPC_ID", "NpcRating.NORMAL", "TARGET_UNSPAWNED", "TARGET_DEAD", "TARGET_INVULNERABLE",
			"TARGET_NOT_HOSTILE", "getKnownList().knows", "getKnownList().sees", "PositionUtil.isInAttackRange", "GeoService.getInstance().canSee",
			"isPowerShardEquipped", "hasGodStone", "highestAttemptedEvent", "FIRST_SPIKE_SPAWN_X = 1234.05f",
			"FIRST_SPIKE_SPAWN_Y = 1042.47f", "FIRST_SPIKE_SPAWN_Z = 144.726f", "getSpawn().isTemporarySpawn()",
			"getSpawn().isEventSpawn()"))
			assertTrue(source.contains(required), required);
		assertTrue(source.contains("FIRST_SPIKE_MAP_ID = 210010000"));
	}

	@Test
	void onlyRealOwnerAttackObserverProducesSignalsAndNoCombatSchedulerExists() throws IOException {
		String source = read("services/ai/combat/OwnerAttackSignalSource.java");
		assertTrue(source.contains("new ActionObserver(ObserverType.ATTACK)"));
		assertTrue(source.contains("public void attack(Creature target, int skillId)"));
		assertTrue(source.contains("owner.getObserveController().addObserver(observer)"));
		assertTrue(source.contains("owner.getObserveController().removeObserver(observer)"));
		for (String forbidden : List.of("getTarget()", "findNearest", "ThreadPoolManager", "ScheduledExecutor", "new Thread", "schedule("))
			assertFalse(source.contains(forbidden), forbidden);
	}

	@Test
	void contributionProjectionChangesOnlyFinalAndTransferBoundaries() throws IOException {
		String damageList = read("controllers/attack/DamageList.java");
		String aggroList = read("controllers/attack/AggroList.java");
		String npcController = read("controllers/NpcController.java");
		assertTrue(damageList.contains("Context.FINAL_REWARD"));
		assertTrue(aggroList.contains("Context.TRANSFER_ON_REMOVE"));
		assertTrue(aggroList.contains("synchronized (contributionLock)"));
		assertTrue(aggroList.contains("List.copyOf(aggroList.values())"));
		assertTrue(aggroList.contains("addDamageAndHate(attacker, damage, hate)"));
		assertTrue(npcController.contains("super.onAttack(actingCreature"));
		assertFalse(npcController.contains("resolve(actingCreature"));
		assertFalse(read("model/gameobjects/player/Player.java").contains("CombatContributionOwnerResolver"));
	}

	@Test
	void companionQuestAttackHooksAreSuppressedButNativeKillPathRemains() throws IOException {
		String npcController = read("controllers/NpcController.java");
		String playerController = read("controllers/PlayerController.java");
		assertTrue(npcController.contains("!CombatContributionOwnerResolver.getInstance().isPersonalCompanion(attacker)"));
		assertTrue(npcController.contains("!CombatContributionOwnerResolver.getInstance().isPersonalCompanion(actingCreature)"));
		assertEquals(2, count(playerController, "CombatContributionOwnerResolver.getInstance().isPersonalCompanion(getOwner())"));
		assertEquals(1, count(playerController, "!CombatContributionOwnerResolver.getInstance().isPersonalCompanion(getOwner())"));
		assertEquals(1, count(npcController, "QuestEngine.getInstance().onKill"));
		assertFalse(read("services/ai/combat/DefaultCombatActionGateway.java").contains("QuestEngine"));
	}

	@Test
	void syntheticDeathReturnsBeforeRealPlayerRewardAndResurrectionFlow() throws IOException {
		String playerController = read("controllers/PlayerController.java");
		int branch = playerController.indexOf("CompanionService.getInstance().isActiveCompanion(player)");
		int rebirth = playerController.indexOf("setRebirthReviveInfo();", branch);
		int resurrection = playerController.indexOf("scheduleShowResurrectionOptions();", branch);
		int pvpReward = playerController.indexOf("doReward();", branch);
		int questDeath = playerController.indexOf("QuestEngine.getInstance().onDie", branch);
		assertTrue(branch > 0);
		assertTrue(playerController.indexOf("return;", branch) < rebirth);
		assertTrue(rebirth < resurrection && resurrection < pvpReward && pvpReward < questDeath);
		assertTrue(playerController.contains("super.onDie(lastAttacker)"));
		assertTrue(playerController.contains("player.getLifeStats().cancelAllTasks()"));
	}

	@Test
	void lifecycleTransfersBeforeUnregisterAndNeverPersistsTemplate() throws IOException {
		String service = read("services/ai/CompanionService.java");
		int beginRemoval = service.indexOf("contributionResolver.beginRemoval");
		int worldRemoval = service.indexOf("World.getInstance().removeObject(companion)", beginRemoval);
		int registryRemoval = service.indexOf("registry.unregister(controlled)", worldRemoval);
		int attributionRemoval = service.indexOf("contributionResolver.unregister", registryRemoval);
		assertTrue(beginRemoval < worldRemoval && worldRemoval < registryRemoval && registryRemoval < attributionRemoval);
		for (String forbidden : List.of("PlayerEnterWorldService", "PlayerLeaveWorldService", "PlayerService.storePlayer", "PlayerDAO.",
			"InventoryDAO", "setOnline(true)", "new AionConnection"))
			assertFalse(service.contains(forbidden), forbidden);
		assertTrue(service.contains("ownerAttackObserver"));
		assertTrue(service.contains("combatAttribution"));
	}

	@Test
	void featureFlagsAreDefaultOffAndCompanionStaysIndependentFromStageOneFlag() throws IOException {
		String config = Files.readString(Path.of("config/main/ai.properties"));
		for (String flag : List.of("ai.companions.combat.enabled = false", "ai.companions.combat.basic_attack.enabled = false",
			"ai.companions.combat.owner_attribution.enabled = false", "ai.companions.combat.allowed_npc_ids ="))
			assertTrue(config.contains(flag), flag);
		String service = read("services/ai/CompanionService.java");
		assertFalse(service.contains("AIConfig.SYNTHETIC_PLAYERS_ENABLED"));
	}

	private static String read(String relativePath) throws IOException {
		return Files.readString(SOURCE.resolve(relativePath));
	}

	private static int count(String source, String token) {
		int count = 0;
		for (int index = 0; (index = source.indexOf(token, index)) >= 0; index += token.length())
			count++;
		return count;
	}
}
