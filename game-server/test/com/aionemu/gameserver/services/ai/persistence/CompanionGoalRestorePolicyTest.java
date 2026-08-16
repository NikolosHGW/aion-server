package com.aionemu.gameserver.services.ai.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.services.ai.persistence.CompanionGoalRestorePolicy.RestoreResult;
import com.aionemu.gameserver.services.ai.quest.QuestGoalPlan;
import com.aionemu.gameserver.services.ai.quest.QuestGoalStep;

class CompanionGoalRestorePolicyTest {

	@Test
	void exactBindingVersionAndSemanticPlanRestore() {
		QuestGoalPlan plan = plan(3);
		CompanionPersistentState state = state(10, 20, QuestGoalSemanticFingerprint.PLAN_VERSION,
			QuestGoalSemanticFingerprint.sha256(plan));

		assertEquals(RestoreResult.RESTORED, CompanionGoalRestorePolicy.validate(state, 10, 20, plan));
	}

	@Test
	void identityVersionTargetAndSemanticDriftFailClosed() {
		QuestGoalPlan plan = plan(3);
		String fingerprint = QuestGoalSemanticFingerprint.sha256(plan);
		assertEquals(RestoreResult.BINDING_MISMATCH, CompanionGoalRestorePolicy.validate(state(10, 20, 1, fingerprint), 11, 20, plan));
		assertEquals(RestoreResult.PLAN_VERSION_MISMATCH,
			CompanionGoalRestorePolicy.validate(state(10, 20, 2, fingerprint), 10, 20, plan));
		assertEquals(RestoreResult.TARGET_MISMATCH,
			CompanionGoalRestorePolicy.validate(state(10, 20, 1, fingerprint, 1103), 10, 20, plan));
		assertEquals(RestoreResult.SEMANTIC_FINGERPRINT_MISMATCH,
			CompanionGoalRestorePolicy.validate(state(10, 20, 1, fingerprint), 10, 20, plan(4)));
	}

	@Test
	void bindingWithoutGoalIsNotInvented() {
		CompanionPersistentState state = new CompanionPersistentState(
			new CompanionBinding(10, 20, CompanionGoalRestorePolicy.ROLE_PERSONAL_COMPANION), Optional.empty());

		assertEquals(RestoreResult.NO_PERSISTED_GOAL, CompanionGoalRestorePolicy.validate(state, 10, 20, plan(3)));
	}

	@Test
	void foreignRoleAndUnknownGoalTypeAreRejected() {
		QuestGoalPlan plan = plan(3);
		String fingerprint = QuestGoalSemanticFingerprint.sha256(plan);
		CompanionBinding foreignRole = new CompanionBinding(10, 20, "CITIZEN_AI");
		PersistedGoalIntent validGoal = new PersistedGoalIntent(10, CompanionGoalRestorePolicy.GOAL_TYPE_COMPLETE_QUEST, 1102, 1, fingerprint);
		assertEquals(RestoreResult.BINDING_MISMATCH,
			CompanionGoalRestorePolicy.validate(new CompanionPersistentState(foreignRole, Optional.of(validGoal)), 10, 20, plan));

		CompanionBinding binding = new CompanionBinding(10, 20, CompanionGoalRestorePolicy.ROLE_PERSONAL_COMPANION);
		PersistedGoalIntent unknownGoal = new PersistedGoalIntent(10, "OBTAIN_ITEM", 1102, 1, fingerprint);
		assertEquals(RestoreResult.GOAL_TYPE_MISMATCH,
			CompanionGoalRestorePolicy.validate(new CompanionPersistentState(binding, Optional.of(unknownGoal)), 10, 20, plan));
	}

	private static CompanionPersistentState state(int ownerId, int companionId, int version, String fingerprint) {
		return state(ownerId, companionId, version, fingerprint, 1102);
	}

	private static CompanionPersistentState state(int ownerId, int companionId, int version, String fingerprint, int targetId) {
		CompanionBinding binding = new CompanionBinding(ownerId, companionId, CompanionGoalRestorePolicy.ROLE_PERSONAL_COMPANION);
		PersistedGoalIntent goal = new PersistedGoalIntent(ownerId, CompanionGoalRestorePolicy.GOAL_TYPE_COMPLETE_QUEST, targetId, version,
			fingerprint);
		return new CompanionPersistentState(binding, Optional.of(goal));
	}

	private static QuestGoalPlan plan(int count) {
		return new QuestGoalPlan(1102, "raw", "localized", "reason", 1, 9, List.of(1101),
			List.of(new QuestGoalStep(QuestGoalStep.Type.KILL_NPC_SET, List.of(210133), List.of("Kerub"), count,
				new QuestGoalStep.ReferenceCoordinate(210010000, 210133, 1, 1, 2, 3))),
			List.of("exp=180"), List.of("reference"));
	}
}
