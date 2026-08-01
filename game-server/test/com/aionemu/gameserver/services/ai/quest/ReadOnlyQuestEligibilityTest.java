package com.aionemu.gameserver.services.ai.quest;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.dataholders.QuestsData;
import com.aionemu.gameserver.configs.main.CustomConfig;
import com.aionemu.gameserver.configs.main.MembershipConfig;
import com.aionemu.gameserver.model.Gender;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.account.Account;
import com.aionemu.gameserver.model.account.PlayerAccountData;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.player.PlayerAppearance;
import com.aionemu.gameserver.model.gameobjects.player.PlayerCommonData;
import com.aionemu.gameserver.model.gameobjects.player.AbyssRank;
import com.aionemu.gameserver.model.gameobjects.player.Equipment;
import com.aionemu.gameserver.model.gameobjects.player.QuestStateList;
import com.aionemu.gameserver.model.items.storage.PlayerStorage;
import com.aionemu.gameserver.model.items.storage.StorageType;
import com.aionemu.gameserver.model.templates.quest.XMLStartCondition;
import com.aionemu.gameserver.model.templates.QuestTemplate;
import com.aionemu.gameserver.model.templates.quest.FinishedQuestCond;
import com.aionemu.gameserver.questEngine.model.QuestState;
import com.aionemu.gameserver.questEngine.model.QuestStatus;
import com.aionemu.gameserver.services.QuestService;
import com.aionemu.gameserver.services.QuestService.StartEligibilityReason;
import com.aionemu.gameserver.utils.stats.AbyssRankEnum;

import sun.misc.Unsafe;

class ReadOnlyQuestEligibilityTest {

	@Test
	void fullSilentConditionChecksEquipmentWhileLegacyWarnFalseSemanticsRemainUnchanged() throws Exception {
		Player player = player();
		XMLStartCondition condition = new XMLStartCondition();
		set(condition, "equipped", List.of(999999));

		assertTrue(condition.check(player, false), "Legacy overload must retain its historical warn=false behavior");
		assertFalse(condition.checkFullSilentReadOnly(player), "New read-only API must still enforce required equipment");
		assertNull(player.getClientConnection());
		assertTrue(player.getQuestStateList().getAllQuestState().isEmpty());
		assertTrue(player.getInventory().getItems().isEmpty());
	}

	@Test
	void unknownQuestCheckReturnsStructuredReasonWithoutMutatingPlayerDomains() {
		QuestsData previous = DataManager.QUEST_DATA;
		DataManager.QUEST_DATA = new QuestsData();
		try {
			Player player = player();
			long expBefore = player.getCommonData().getExp();
			int questCountBefore = player.getQuestStateList().getAllQuestState().size();
			int inventoryCountBefore = player.getInventory().getItems().size();

			QuestService.StartEligibilityResult result = QuestService.checkStartConditionsFullSilentReadOnly(player, 999999);

			assertFalse(result.eligible());
			assertEquals(StartEligibilityReason.UNKNOWN_QUEST, result.reason());
			assertEquals(expBefore, player.getCommonData().getExp());
			assertEquals(questCountBefore, player.getQuestStateList().getAllQuestState().size());
			assertEquals(inventoryCountBefore, player.getInventory().getItems().size());
			assertNull(player.getClientConnection());
		} finally {
			DataManager.QUEST_DATA = previous;
		}
	}

	@Test
	void activeCompletedRaceClassGenderLevelAndRankFailuresHaveStableReasons() throws Exception {
		QuestsData previous = DataManager.QUEST_DATA;
		try {
			QuestTemplate template = template(1102);
			install(template);
			Player active = player();
			QuestState activeState = new QuestState(1102, QuestStatus.START);
			active.getQuestStateList().addQuest(1102, activeState);
			assertReason(active, 1102, StartEligibilityReason.ALREADY_ACTIVE);
			assertEquals(QuestStatus.START, activeState.getStatus());
			assertEquals(0, activeState.getQuestVars().getQuestVars());
			assertEquals(com.aionemu.gameserver.model.gameobjects.Persistable.PersistentState.NEW, activeState.getPersistentState());

			Player completed = player();
			completed.getQuestStateList().addQuest(1102, new QuestState(1102, QuestStatus.COMPLETE));
			assertReason(completed, 1102, StartEligibilityReason.NOT_REPEATABLE);

			set(template, "racePermitted", Race.ASMODIANS);
			assertReason(player(), 1102, StartEligibilityReason.WRONG_RACE);
			set(template, "racePermitted", null);

			set(template, "classPermitted", List.of(PlayerClass.MAGE));
			assertReason(player(), 1102, StartEligibilityReason.WRONG_CLASS);
			set(template, "classPermitted", null);

			set(template, "genderPermitted", Gender.FEMALE);
			assertReason(player(), 1102, StartEligibilityReason.WRONG_GENDER);
			set(template, "genderPermitted", null);

			set(template, "minlevelPermitted", 1);
			assertReason(player(), 1102, StartEligibilityReason.LEVEL_TOO_LOW);
			set(template, "minlevelPermitted", 0);

			set(template, "rank", 2);
			Player lowRank = player();
			AbyssRank abyssRank = (AbyssRank) unsafe().allocateInstance(AbyssRank.class);
			set(abyssRank, "rank", AbyssRankEnum.GRADE9_SOLDIER);
			lowRank.setAbyssRank(abyssRank);
			assertReason(lowRank, 1102, StartEligibilityReason.RANK_TOO_LOW);
		} finally {
			DataManager.QUEST_DATA = previous;
		}
	}

	@Test
	void prerequisiteTitleCapacityAndMembershipExceptionUseTheSameReadOnlyCapacityRule() throws Exception {
		QuestsData previous = DataManager.QUEST_DATA;
		int previousLimit = CustomConfig.BASIC_QUEST_SIZE_LIMIT;
		byte previousPermission = MembershipConfig.QUEST_LIMIT_DISABLED;
		try {
			QuestTemplate target = template(1102);
			QuestTemplate activeTemplate = template(1200);
			XMLStartCondition condition = new XMLStartCondition();
			FinishedQuestCond prerequisite = new FinishedQuestCond();
			set(prerequisite, "questId", 1101);
			set(condition, "finished", List.of(prerequisite));
			set(target, "startConds", List.of(condition));
			install(target, activeTemplate, template(1101));

			Player unmet = player();
			assertReason(unmet, 1102, StartEligibilityReason.START_CONDITION_NOT_MET);
			unmet.getQuestStateList().addQuest(1101, new QuestState(1101, QuestStatus.COMPLETE));
			assertEquals(StartEligibilityReason.ELIGIBLE, QuestService.checkStartConditionsFullSilentReadOnly(unmet, 1102).reason());

			set(condition, "finished", null);
			set(condition, "requiredTitle", 7);
			assertReason(player(), 1102, StartEligibilityReason.START_CONDITION_NOT_MET);
			set(condition, "requiredTitle", 0);

			CustomConfig.BASIC_QUEST_SIZE_LIMIT = 1;
			MembershipConfig.QUEST_LIMIT_DISABLED = 10;
			Player full = player();
			full.getQuestStateList().addQuest(1200, new QuestState(1200, QuestStatus.START));
			assertReason(full, 1102, StartEligibilityReason.QUEST_LOG_FULL);
			full.getAccount().setMembership((byte) 10);
			assertEquals(StartEligibilityReason.ELIGIBLE, QuestService.checkStartConditionsFullSilentReadOnly(full, 1102).reason());
			assertEquals(1, full.getQuestStateList().getAllQuestState().size());
		} finally {
			DataManager.QUEST_DATA = previous;
			CustomConfig.BASIC_QUEST_SIZE_LIMIT = previousLimit;
			MembershipConfig.QUEST_LIMIT_DISABLED = previousPermission;
		}
	}

	private void assertReason(Player player, int questId, StartEligibilityReason expected) {
		assertEquals(expected, QuestService.checkStartConditionsFullSilentReadOnly(player, questId).reason());
	}

	private QuestTemplate template(int id) throws ReflectiveOperationException {
		QuestTemplate template = new QuestTemplate();
		set(template, "id", id);
		set(template, "name", "Test " + id);
		return template;
	}

	@SuppressWarnings("unchecked")
	private void install(QuestTemplate... templates) throws ReflectiveOperationException {
		QuestsData data = new QuestsData();
		Field field = QuestsData.class.getDeclaredField("questTemplates");
		field.setAccessible(true);
		Map<Integer, QuestTemplate> values = (Map<Integer, QuestTemplate>) field.get(data);
		for (QuestTemplate template : templates)
			values.put(template.getId(), template);
		DataManager.QUEST_DATA = data;
	}

	private Player player() {
		PlayerCommonData common = new PlayerCommonData(100);
		common.setName("ReadOnlyOwner");
		common.setRace(Race.ELYOS);
		common.setPlayerClass(PlayerClass.WARRIOR);
		common.setGender(Gender.MALE);
		PlayerAccountData playerData = new PlayerAccountData(common, new PlayerAppearance());
		try {
			Player player = (Player) unsafe().allocateInstance(Player.class);
			set(player, "playerAccountData", playerData);
			set(player, "playerAccount", new Account(1));
			set(player, "questStateList", new QuestStateList());
			set(player, "equipment", new Equipment(player));
			set(player, "inventory", new PlayerStorage(player, StorageType.CUBE));
			return player;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private Unsafe unsafe() throws ReflectiveOperationException {
		Field field = Unsafe.class.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return (Unsafe) field.get(null);
	}

	private void set(Object target, String fieldName, Object value) throws ReflectiveOperationException {
		Class<?> type = target.getClass();
		while (type != null) {
			try {
				Field field = type.getDeclaredField(fieldName);
				field.setAccessible(true);
				field.set(target, value);
				return;
			} catch (NoSuchFieldException e) {
				type = type.getSuperclass();
			}
		}
		throw new NoSuchFieldException(fieldName);
	}
}
