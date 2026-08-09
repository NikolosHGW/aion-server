package com.aionemu.gameserver.services.ai.quest;

import com.aionemu.gameserver.utils.ChatUtil;

public final class QuestExecutionCommandFormatter {

	private QuestExecutionCommandFormatter() {
	}

	public static String formatTransition(QuestExecutionTransition transition) {
		QuestExecutionSnapshot snapshot = transition.snapshot();
		String quest = ChatUtil.quest(QuestExecutionAllowlist.FIRST_EXECUTION_QUEST_ID);
		return switch (snapshot.executionState()) {
			case CHOSEN_WAITING_ACCEPTANCE -> "Companion quest " + quest + ": accept it yourself from Mires [npcId=203057]; tracking is read-only.";
			case ACTIVE_OBJECTIVE -> "Companion quest " + quest + ": native journal progress " + snapshot.currentProgress() + "/"
				+ snapshot.requiredProgress() + "; targets Striped Kerub [npcId=210133,210134].";
			case READY_TO_TURN_IN -> "Companion quest " + quest + ": objective " + snapshot.currentProgress() + "/"
				+ snapshot.requiredProgress() + "; return to Mires [npcId=203057] and turn it in yourself.";
			case COMPLETED -> "Companion quest " + quest + ": native journal status COMPLETE; runtime tracking finished.";
			case ABANDONED -> "Companion quest " + quest + ": native quest disappeared after acceptance; runtime state ABANDONED.";
			case STALE, BLOCKED -> "Companion quest " + quest + ": tracking " + snapshot.executionState() + "; reason=" + snapshot.reason() + '.';
			default -> "Companion quest " + quest + ": runtime state " + snapshot.executionState() + "; reason=" + snapshot.reason() + '.';
		};
	}
}
