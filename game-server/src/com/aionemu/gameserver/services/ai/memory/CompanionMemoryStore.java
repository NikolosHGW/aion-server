package com.aionemu.gameserver.services.ai.memory;

public interface CompanionMemoryStore {
	/** Returns false on a conflicting source occurrence; never replaces a confirmed fact. */
	boolean saveCandidate(QuestCompletionEvidence evidence) throws Exception;
	int reconcile(String realm, long now, int limit) throws Exception;
	String status(String realm, int ownerId, int accountId) throws Exception;
}
