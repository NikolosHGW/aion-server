package com.aionemu.gameserver.services.ai.persistence;

import com.aionemu.gameserver.services.ai.quest.QuestGoalPlan;

public final class CompanionGoalRestorePolicy {

	public static final String GOAL_TYPE_COMPLETE_QUEST = "COMPLETE_QUEST";
	public static final String ROLE_PERSONAL_COMPANION = "PERSONAL_COMPANION";

	private CompanionGoalRestorePolicy() {
	}

	public static RestoreResult validate(CompanionPersistentState state, int ownerPlayerId, int companionPlayerId, QuestGoalPlan rebuiltPlan) {
		if (state == null)
			return RestoreResult.NO_PERSISTED_STATE;
		CompanionBinding binding = state.binding();
		if (binding.ownerPlayerId() != ownerPlayerId || binding.companionPlayerId() != companionPlayerId
			|| !ROLE_PERSONAL_COMPANION.equals(binding.role()))
			return RestoreResult.BINDING_MISMATCH;
		if (state.goalIntent().isEmpty())
			return RestoreResult.NO_PERSISTED_GOAL;
		PersistedGoalIntent intent = state.goalIntent().get();
		if (!GOAL_TYPE_COMPLETE_QUEST.equals(intent.goalType()))
			return RestoreResult.GOAL_TYPE_MISMATCH;
		if (intent.planVersion() != QuestGoalSemanticFingerprint.PLAN_VERSION)
			return RestoreResult.PLAN_VERSION_MISMATCH;
		if (rebuiltPlan == null || intent.targetId() != rebuiltPlan.questId())
			return RestoreResult.TARGET_MISMATCH;
		if (!intent.semanticFingerprint().equals(QuestGoalSemanticFingerprint.sha256(rebuiltPlan)))
			return RestoreResult.SEMANTIC_FINGERPRINT_MISMATCH;
		return RestoreResult.RESTORED;
	}

	public enum RestoreResult {
		NOT_ATTEMPTED,
		DISABLED,
		NO_PERSISTED_STATE,
		NO_PERSISTED_GOAL,
		BINDING_MISMATCH,
		GOAL_TYPE_MISMATCH,
		PLAN_VERSION_MISMATCH,
		TARGET_MISMATCH,
		SEMANTIC_FINGERPRINT_MISMATCH,
		PLAN_REBUILD_FAILED,
		CONFIGURATION_REJECTED,
		DATABASE_ERROR,
		RESTORED,
		SAVED,
		CLEARED
	}
}
