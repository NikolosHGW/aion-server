package com.aionemu.gameserver.services.ai.quest;

import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.QuestService;
import com.aionemu.gameserver.services.QuestService.StartEligibilityReason;
import com.aionemu.gameserver.services.QuestService.StartEligibilityResult;

public final class ReadOnlyQuestEligibility {

	public EligibilityResult check(Player owner, int questId) {
		StartEligibilityResult result = QuestService.checkStartConditionsFullSilentReadOnly(owner, questId);
		return new EligibilityResult(result.eligible(), map(result.reason()));
	}

	private QuestGoalReason map(StartEligibilityReason reason) {
		return switch (reason) {
			case ELIGIBLE -> QuestGoalReason.ELIGIBLE;
			case UNKNOWN_QUEST -> QuestGoalReason.UNKNOWN_QUEST;
			case ALREADY_ACTIVE -> QuestGoalReason.INELIGIBLE_ALREADY_ACTIVE;
			case NOT_REPEATABLE -> QuestGoalReason.INELIGIBLE_NOT_REPEATABLE;
			case WRONG_RACE -> QuestGoalReason.INELIGIBLE_WRONG_RACE;
			case LEVEL_TOO_LOW -> QuestGoalReason.INELIGIBLE_LEVEL_TOO_LOW;
			case LEVEL_TOO_HIGH -> QuestGoalReason.INELIGIBLE_LEVEL_TOO_HIGH;
			case WRONG_CLASS -> QuestGoalReason.INELIGIBLE_WRONG_CLASS;
			case WRONG_GENDER -> QuestGoalReason.INELIGIBLE_WRONG_GENDER;
			case RANK_TOO_LOW -> QuestGoalReason.INELIGIBLE_RANK_TOO_LOW;
			case START_CONDITION_NOT_MET -> QuestGoalReason.INELIGIBLE_START_CONDITION;
			case MISSING_INVENTORY_ITEM -> QuestGoalReason.INELIGIBLE_MISSING_INVENTORY_ITEM;
			case COMBINE_SKILL_NOT_MET -> QuestGoalReason.INELIGIBLE_COMBINE_SKILL;
			case NPC_FACTION_UNAVAILABLE -> QuestGoalReason.INELIGIBLE_NPC_FACTION;
			case QUEST_LOG_FULL -> QuestGoalReason.INELIGIBLE_QUEST_LOG_FULL;
			case CHECK_FAILED -> QuestGoalReason.INELIGIBLE_CHECK_FAILED;
		};
	}

	public record EligibilityResult(boolean eligible, QuestGoalReason reason) {
	}
}
