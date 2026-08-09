package com.aionemu.gameserver.services.ai.quest;

public enum QuestExecutionState {
	NO_GOAL,
	OFFERED,
	CHOSEN_WAITING_ACCEPTANCE,
	ACTIVE_OBJECTIVE,
	READY_TO_TURN_IN,
	COMPLETED,
	ABANDONED,
	STALE,
	BLOCKED
}
