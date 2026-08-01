package com.aionemu.gameserver.services.ai.quest;

import java.util.List;

public record QuestGoalStep(Type type, List<Integer> targetIds, List<String> localizedTargetNames, int requiredCount,
	ReferenceCoordinate referenceCoordinate) {

	public QuestGoalStep {
		targetIds = List.copyOf(targetIds);
		localizedTargetNames = List.copyOf(localizedTargetNames);
		if (targetIds.isEmpty() || targetIds.size() != localizedTargetNames.size())
			throw new IllegalArgumentException("A goal step needs matching target IDs and names");
		if (requiredCount <= 0)
			throw new IllegalArgumentException("Goal step count must be positive");
	}

	public enum Type {
		TALK_TO_START_NPC,
		KILL_NPC_SET,
		REPORT_TO_END_NPC
	}

	public record ReferenceCoordinate(int mapId, int npcId, int staticId, float x, float y, float z) {

		public ReferenceCoordinate {
			if (mapId <= 0 || npcId <= 0 || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
				throw new IllegalArgumentException("Invalid reference coordinate");
		}
	}
}
