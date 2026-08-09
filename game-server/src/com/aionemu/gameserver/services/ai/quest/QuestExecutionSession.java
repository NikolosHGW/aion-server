package com.aionemu.gameserver.services.ai.quest;

public record QuestExecutionSession(int ownerObjectId, int companionObjectId, long companionSessionId, QuestExecutionRole role,
	QuestExecutionPlan plan) {

	public QuestExecutionSession {
		if (ownerObjectId <= 0 || companionObjectId <= 0 || companionSessionId <= 0 || role != QuestExecutionRole.PERSONAL_COMPANION || plan == null)
			throw new IllegalArgumentException("Invalid personal companion quest execution identity");
	}

	public int questId() {
		return plan.questId();
	}
}
