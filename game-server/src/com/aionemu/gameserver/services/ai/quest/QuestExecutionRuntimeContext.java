package com.aionemu.gameserver.services.ai.quest;

public record QuestExecutionRuntimeContext(int ownerObjectId, int companionObjectId, long companionSessionId, QuestExecutionRole role,
	int ownerMapId, int ownerInstanceId, int companionMapId, int companionInstanceId, boolean ownerConnected, boolean ownerSpawned,
	boolean companionSpawned) {

	public boolean identityMatches(QuestExecutionSession session) {
		return ownerObjectId == session.ownerObjectId() && companionObjectId == session.companionObjectId()
			&& companionSessionId == session.companionSessionId() && role == session.role();
	}

	public boolean sameMapAndInstance() {
		return ownerMapId == companionMapId && ownerInstanceId == companionInstanceId;
	}

	public boolean active() {
		return ownerConnected && ownerSpawned && companionSpawned;
	}
}
