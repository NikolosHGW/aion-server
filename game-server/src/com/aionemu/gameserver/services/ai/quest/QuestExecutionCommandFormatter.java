package com.aionemu.gameserver.services.ai.quest;

import com.aionemu.gameserver.utils.ChatUtil;

public final class QuestExecutionCommandFormatter {

	private QuestExecutionCommandFormatter() {
	}

	public static String formatTransition(QuestExecutionTransition transition) {
		return formatTransition(QuestExecutionManifest.curated1102(), transition);
	}

	public static String formatTransition(QuestExecutionManifest manifest, QuestExecutionTransition transition) {
		QuestExecutionSnapshot snapshot = transition.snapshot();
		String quest = ChatUtil.quest(manifest.questId());
		return switch (snapshot.executionState()) {
			case CHOSEN_WAITING_ACCEPTANCE -> "Companion quest " + quest + ": accept it yourself from NPC " + manifest.startNpcId() + "; tracking is read-only.";
			case ACTIVE_OBJECTIVE -> "Companion quest " + quest + ": native journal progress " + snapshot.currentProgress() + "/"
				+ snapshot.requiredProgress() + "; targets " + manifest.targetIds() + ".";
			case READY_TO_TURN_IN -> "Companion quest " + quest + ": objective " + snapshot.currentProgress() + "/"
				+ snapshot.requiredProgress() + "; return to NPC " + manifest.endNpcId() + " and turn it in yourself.";
			case COMPLETED -> "Companion quest " + quest + ": native journal status COMPLETE; runtime tracking finished.";
			case ABANDONED -> "Companion quest " + quest + ": native quest disappeared after acceptance; runtime state ABANDONED.";
			case STALE, BLOCKED -> "Companion quest " + quest + ": tracking " + snapshot.executionState() + "; reason=" + snapshot.reason() + '.';
			default -> "Companion quest " + quest + ": runtime state " + snapshot.executionState() + "; reason=" + snapshot.reason() + '.';
		};
	}
}
