package com.aionemu.gameserver.services.ai.creation;

import java.util.Optional;

import com.aionemu.gameserver.services.ai.persistence.CompanionBinding;
import com.aionemu.gameserver.services.ai.persistence.CompanionGoalRestorePolicy;
import com.aionemu.gameserver.services.ai.persistence.CompanionPersistenceRepository;
import com.aionemu.gameserver.services.ai.persistence.CompanionPersistentState;

public final class CompanionCreationService {

	private final CompanionPersistenceRepository repository;
	private final CompanionPlayerCreationGateway playerCreationGateway;

	public CompanionCreationService(CompanionPersistenceRepository repository, CompanionPlayerCreationGateway playerCreationGateway) {
		this.repository = repository;
		this.playerCreationGateway = playerCreationGateway;
	}

	public CompanionCreationResult create(CompanionCreationRequest request, int hostAccountId, String hostAccountName) {
		Optional<CompanionPersistentState> existing = repository.load(request.ownerPlayerId());
		if (existing.isPresent())
			return new CompanionCreationResult(CompanionCreationResult.Status.ALREADY_EXISTS,
				resolveOwnedBody(request, hostAccountId, hostAccountName, existing.get()));

		String databaseName = CompanionCreationPolicy.databaseName(request.ownerPlayerId());
		Optional<CompanionCreationClaim> claim = repository.loadCreationClaim(request.ownerPlayerId());
		boolean recovering = claim.isPresent();
		if (claim.isEmpty()) {
			if (repository.findCompanionBodyByName(databaseName).isPresent())
				throw new IllegalStateException("Deterministic companion name is already occupied without an ownership claim");
			CompanionCreationClaim reservation = new CompanionCreationClaim(request.ownerPlayerId(), databaseName, hostAccountId, hostAccountName, null);
			repository.reserveCreation(reservation);
			claim = Optional.of(reservation);
		}
		CompanionCreationClaim exactClaim = claim.orElseThrow();
		CompanionCreationPolicy.validateClaim(exactClaim, request, hostAccountId, hostAccountName);

		CompanionBodyRecord body;
		if (exactClaim.companionPlayerId() != null) {
			body = repository.loadCompanionBody(exactClaim.companionPlayerId())
				.orElseThrow(() -> new IllegalStateException("Claimed companion player body is missing"));
		} else {
			body = repository.findCompanionBodyByName(databaseName)
				.orElseGet(() -> playerCreationGateway.create(request, databaseName, hostAccountId, hostAccountName));
		}
		CompanionBinding binding = repository.finalizeCreation(exactClaim, body);
		CompanionPersistentState state = new CompanionPersistentState(binding, Optional.empty());
		CompanionBodyRecord resolved = resolveOwnedBody(request, hostAccountId, hostAccountName, state);
		return new CompanionCreationResult(recovering ? CompanionCreationResult.Status.RECOVERED : CompanionCreationResult.Status.CREATED, resolved);
	}

	public CompanionBodyRecord resolveOwnedBody(CompanionCreationRequest request, int hostAccountId, String hostAccountName) {
		CompanionPersistentState state = repository.load(request.ownerPlayerId())
			.orElseThrow(() -> new IllegalStateException("No persistent companion; use companion create first"));
		return resolveOwnedBody(request, hostAccountId, hostAccountName, state);
	}

	private CompanionBodyRecord resolveOwnedBody(CompanionCreationRequest request, int hostAccountId, String hostAccountName,
		CompanionPersistentState state) {
		CompanionCreationClaim claim = repository.loadCreationClaim(request.ownerPlayerId())
			.orElseThrow(() -> new IllegalStateException("Companion binding has no product creation claim"));
		CompanionBodyRecord body = repository.loadCompanionBody(state.binding().companionPlayerId())
			.orElseThrow(() -> new IllegalStateException("Persistent companion player body is missing"));
		CompanionCreationPolicy.validateOwnedBody(state.binding(), claim, request, body, hostAccountId, hostAccountName);
		return body;
	}

	public static CompanionBinding binding(int ownerPlayerId, int companionPlayerId) {
		return new CompanionBinding(ownerPlayerId, companionPlayerId, CompanionGoalRestorePolicy.ROLE_PERSONAL_COMPANION);
	}
}
