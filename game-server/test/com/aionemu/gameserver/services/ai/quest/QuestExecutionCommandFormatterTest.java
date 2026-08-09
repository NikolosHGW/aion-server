package com.aionemu.gameserver.services.ai.quest;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.questEngine.model.QuestStatus;

class QuestExecutionCommandFormatterTest {

	@Test
	void transitionTextUsesOnlyNativeSnapshotAndDeterministicNextAction() {
		assertTrue(format(QuestExecutionState.CHOSEN_WAITING_ACCEPTANCE, null, 0, "WAITING_NATIVE_ACCEPTANCE")
			.contains("accept it yourself"));
		assertTrue(format(QuestExecutionState.ACTIVE_OBJECTIVE, QuestStatus.START, 2, "NATIVE_QUEST_ACTIVE")
			.contains("native journal progress 2/3"));
		assertTrue(format(QuestExecutionState.READY_TO_TURN_IN, QuestStatus.START, 3, "OBJECTIVE_COMPLETE")
			.contains("turn it in yourself"));
		assertTrue(format(QuestExecutionState.COMPLETED, QuestStatus.COMPLETE, 0, "NATIVE_QUEST_COMPLETE")
			.contains("native journal status COMPLETE"));
		assertTrue(format(QuestExecutionState.ABANDONED, null, 0, "NATIVE_QUEST_REMOVED")
			.contains("ABANDONED"));
	}

	private static String format(QuestExecutionState state, QuestStatus nativeStatus, int progress, String reason) {
		QuestExecutionSnapshot snapshot = new QuestExecutionSnapshot(nativeStatus != null, nativeStatus, progress, progress, 3, 0, 10, 20, 30,
			QuestExecutionRole.PERSONAL_COMPANION, 210010000, 1, 210010000, 1, state, nextStep(state), reason);
		return QuestExecutionCommandFormatter.formatTransition(new QuestExecutionTransition(QuestExecutionState.NO_GOAL, state, snapshot));
	}

	private static QuestExecutionNextStep nextStep(QuestExecutionState state) {
		return switch (state) {
			case CHOSEN_WAITING_ACCEPTANCE -> QuestExecutionNextStep.ACCEPT_AT_START_NPC;
			case ACTIVE_OBJECTIVE -> QuestExecutionNextStep.KILL_TARGETS;
			case READY_TO_TURN_IN -> QuestExecutionNextStep.RETURN_TO_END_NPC;
			case COMPLETED -> QuestExecutionNextStep.QUEST_COMPLETE;
			default -> QuestExecutionNextStep.REPLAN;
		};
	}
}
