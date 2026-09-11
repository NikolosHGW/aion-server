package com.aionemu.gameserver.services.ai.creation;

public interface CompanionPlayerCreationGateway {

	CompanionBodyRecord create(CompanionCreationRequest request, String databaseName, int hostAccountId, String hostAccountName);
}
