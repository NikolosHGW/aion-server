package com.aionemu.gameserver.services.ai.quest;

public record QuestExecutionPlan(QuestGoalPlan goalPlan, QuestExecutionManifest manifest) {

	public QuestExecutionPlan {
		if (goalPlan == null || manifest == null || goalPlan.questId() != manifest.questId())
			throw new IllegalArgumentException("Execution plan and manifest must describe the same quest");
	}

	public int questId() {
		return manifest.questId();
	}
}
