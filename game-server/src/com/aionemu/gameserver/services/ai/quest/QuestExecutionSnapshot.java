package com.aionemu.gameserver.services.ai.quest;

import com.aionemu.gameserver.questEngine.model.QuestStatus;

public record QuestExecutionSnapshot(boolean nativeQuestPresent, QuestStatus nativeQuestStatus, int nativeVar0, int currentProgress,
	int requiredProgress, int completeCount, int ownerObjectId, int companionObjectId, long companionSessionId, QuestExecutionRole role,
	int ownerMapId, int ownerInstanceId, int companionMapId, int companionInstanceId, QuestExecutionState executionState,
	QuestExecutionNextStep nextStep, String reason) {

	public SemanticFingerprint semanticFingerprint() {
		return new SemanticFingerprint(executionState, currentProgress, requiredProgress, nextStep, reason);
	}

	public record SemanticFingerprint(QuestExecutionState state, int currentProgress, int requiredProgress, QuestExecutionNextStep nextStep,
		String reason) {
	}
}
