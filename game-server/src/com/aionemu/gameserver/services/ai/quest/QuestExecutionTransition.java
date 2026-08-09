package com.aionemu.gameserver.services.ai.quest;

public record QuestExecutionTransition(QuestExecutionState previousState, QuestExecutionState newState, QuestExecutionSnapshot snapshot) {
}
