package com.aionemu.gameserver.services.ai.quest;

import java.util.List;

public record QuestGoalPlan(int questId, String rawName, String localizedQuestName, String selectionReason, int minimumLevel,
	double distanceToStartSquared, List<Integer> prerequisiteQuestIds, List<QuestGoalStep> steps, List<String> rewardSummaries,
	List<String> limitations) {

	public QuestGoalPlan {
		if (questId <= 0 || steps.isEmpty() || !Double.isFinite(distanceToStartSquared))
			throw new IllegalArgumentException("Invalid quest goal plan");
		prerequisiteQuestIds = List.copyOf(prerequisiteQuestIds);
		steps = List.copyOf(steps);
		rewardSummaries = List.copyOf(rewardSummaries);
		limitations = List.copyOf(limitations);
	}

	public QuestGoalPlan withSelectionReason(String reason) {
		return new QuestGoalPlan(questId, rawName, localizedQuestName, reason, minimumLevel, distanceToStartSquared, prerequisiteQuestIds, steps,
			rewardSummaries, limitations);
	}

	public int totalObjectiveCount() {
		return steps.stream().filter(step -> step.type() == QuestGoalStep.Type.KILL_NPC_SET).mapToInt(QuestGoalStep::requiredCount).sum();
	}

	/** Stable quest semantics only. Runtime distance, presentation text and selected reference coordinates are intentionally excluded. */
	public SemanticFingerprint semanticFingerprint() {
		List<StepFingerprint> stableSteps = steps.stream()
			.map(step -> new StepFingerprint(step.type(), step.targetIds(), step.requiredCount()))
			.toList();
		return new SemanticFingerprint(questId, minimumLevel, prerequisiteQuestIds, stableSteps);
	}

	public record SemanticFingerprint(int questId, int minimumLevel, List<Integer> prerequisiteQuestIds, List<StepFingerprint> steps) {

		public SemanticFingerprint {
			prerequisiteQuestIds = List.copyOf(prerequisiteQuestIds);
			steps = List.copyOf(steps);
		}
	}

	public record StepFingerprint(QuestGoalStep.Type type, List<Integer> targetIds, int requiredCount) {

		public StepFingerprint {
			targetIds = List.copyOf(targetIds);
		}
	}
}
