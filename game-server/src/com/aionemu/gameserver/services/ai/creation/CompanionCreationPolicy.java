package com.aionemu.gameserver.services.ai.creation;

import com.aionemu.gameserver.services.ai.persistence.CompanionBinding;
import com.aionemu.gameserver.services.ai.persistence.CompanionGoalRestorePolicy;

public final class CompanionCreationPolicy {

	public static final String STARTING_CLASS = "WARRIOR";

	private CompanionCreationPolicy() {
	}

	public static String databaseName(int ownerPlayerId) {
		if (ownerPlayerId <= 0)
			throw new IllegalArgumentException("Owner player ID must be positive");
		StringBuilder suffix = new StringBuilder();
		for (int value = ownerPlayerId; value > 0; value = (value - 1) / 26)
			suffix.append((char) ('a' + (value - 1) % 26));
		return "Aic" + suffix.reverse();
	}

	public static void validateClaim(CompanionCreationClaim claim, CompanionCreationRequest request, int hostAccountId, String hostAccountName) {
		if (claim.ownerPlayerId() != request.ownerPlayerId() || !claim.databaseName().equals(databaseName(request.ownerPlayerId()))
			|| claim.hostAccountId() != hostAccountId || !claim.hostAccountName().equals(hostAccountName))
			throw new IllegalStateException("Persistent companion creation claim does not match current configuration");
	}

	public static void validateOwnedBody(CompanionBinding binding, CompanionCreationClaim claim, CompanionCreationRequest request,
		CompanionBodyRecord body, int hostAccountId, String hostAccountName) {
		validateClaim(claim, request, hostAccountId, hostAccountName);
		if (binding.ownerPlayerId() != request.ownerPlayerId() || binding.companionPlayerId() != body.playerId()
			|| !CompanionGoalRestorePolicy.ROLE_PERSONAL_COMPANION.equals(binding.role()) || claim.companionPlayerId() == null
			|| claim.companionPlayerId() != body.playerId() || !body.databaseName().equals(claim.databaseName())
			|| body.accountId() != hostAccountId || !body.accountName().equals(hostAccountName) || !body.race().equals(request.race())
			|| !body.gender().equals(request.gender()) || !STARTING_CLASS.equals(body.playerClass()) || body.online()
			|| !body.standardCreationComplete())
			throw new IllegalStateException("Persistent companion body does not match its immutable ownership claim");
	}
}
