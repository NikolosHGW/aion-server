package com.aionemu.gameserver.services.ai.quest;

import java.util.StringJoiner;

import com.aionemu.gameserver.utils.ChatUtil;

public final class QuestGoalCommandFormatter {

	private QuestGoalCommandFormatter() {
	}

	public static String formatProposal(QuestGoalPlan plan) {
		StringBuilder out = new StringBuilder();
		out.append("Runtime quest goal proposal: ").append(ChatUtil.quest(plan.questId())).append(" (ID ").append(plan.questId()).append(")\n");
		out.append("Localized name: ").append(plan.localizedQuestName()).append("; raw diagnostic name: ").append(plan.rawName()).append('\n');
		out.append("Reason: ").append(plan.selectionReason()).append('\n');
		out.append("Prerequisites: ").append(plan.prerequisiteQuestIds().isEmpty() ? "none" : plan.prerequisiteQuestIds()).append('\n');
		for (int i = 0; i < plan.steps().size(); i++) {
			QuestGoalStep step = plan.steps().get(i);
			QuestGoalStep.ReferenceCoordinate ref = step.referenceCoordinate();
			StringJoiner targets = new StringJoiner(", ");
			for (int target = 0; target < step.targetIds().size(); target++)
				targets.add(step.localizedTargetNames().get(target) + " [npcId=" + step.targetIds().get(target) + "]");
			out.append("Step ").append(i + 1).append(": ").append(step.type()).append("; targets=").append(targets).append("; count=")
				.append(step.requiredCount()).append("; mapId=").append(ref.mapId()).append("; reference coordinate=").append(ref.x()).append(',')
				.append(ref.y()).append(',').append(ref.z()).append('\n');
		}
		out.append("Rewards (descriptive only): ").append(plan.rewardSummaries().isEmpty() ? "none" : plan.rewardSummaries()).append('\n');
		out.append("Limitations: ").append(plan.limitations()).append('\n');
		out.append("description unavailable in server data; localized quest link shown above");
		return out.toString();
	}

	public static String formatChosen(QuestGoalPlan plan) {
		return "Chosen quest goal " + ChatUtil.quest(plan.questId()) + " (ID " + plan.questId() + "): runtime AI goal only; quest not accepted";
	}

	public static String formatChoice(CompanionGoalSession.ChoiceResult result) {
		if (result.status() == CompanionGoalSession.ChoiceStatus.CHOSEN)
			return formatChosen(result.plan());
		return "Companion goal choose: " + result.status() + "; reason=" + result.reason();
	}
}
