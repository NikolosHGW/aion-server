package com.aionemu.gameserver.services.ai.quest;

public record QuestGoalProposalResult(Status status, int questId, QuestGoalPlan plan, QuestGoalReason reason) {

	public static QuestGoalProposalResult offered(QuestGoalPlan plan) {
		return new QuestGoalProposalResult(Status.OFFERED, plan.questId(), plan, QuestGoalReason.ELIGIBLE);
	}

	public static QuestGoalProposalResult alreadyComplete(int questId) {
		return new QuestGoalProposalResult(Status.ALREADY_COMPLETE, questId, null, QuestGoalReason.INELIGIBLE_NOT_REPEATABLE);
	}

	public enum Status {
		OFFERED,
		ALREADY_COMPLETE
	}
}
