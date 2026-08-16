package com.aionemu.gameserver.services.ai.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.services.ai.quest.QuestGoalPlan;
import com.aionemu.gameserver.services.ai.quest.QuestGoalStep;

class QuestGoalSemanticFingerprintTest {

	@Test
	void presentationAndRuntimeCoordinatesDoNotChangeFingerprint() {
		QuestGoalPlan first = plan("first", 10, 3, 1);
		QuestGoalPlan second = plan("changed presentation", 999, 3, 55);

		assertEquals(QuestGoalSemanticFingerprint.sha256(first), QuestGoalSemanticFingerprint.sha256(second));
		assertTrue(QuestGoalSemanticFingerprint.sha256(first).matches("[0-9a-f]{64}"));
	}

	@Test
	void stableQuestSemanticChangesAreDetected() {
		assertNotEquals(QuestGoalSemanticFingerprint.sha256(plan("same", 10, 3, 1)),
			QuestGoalSemanticFingerprint.sha256(plan("same", 10, 4, 1)));
	}

	private static QuestGoalPlan plan(String reason, double distance, int count, int staticId) {
		return new QuestGoalPlan(1102, "raw", "localized", reason, 1, distance, List.of(1101),
			List.of(new QuestGoalStep(QuestGoalStep.Type.KILL_NPC_SET, List.of(210133, 210134), List.of("a", "b"), count,
				new QuestGoalStep.ReferenceCoordinate(210010000, 210133, staticId, 1, 2, 3))),
			List.of("reward text"), List.of("limitation text"));
	}
}
