package com.aionemu.gameserver.services.ai.creation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.services.ai.persistence.CompanionBinding;
import com.aionemu.gameserver.services.ai.persistence.CompanionGoalRestorePolicy;
import com.aionemu.gameserver.services.ai.persistence.CompanionPersistenceRepository;
import com.aionemu.gameserver.services.ai.persistence.CompanionPersistentState;
import com.aionemu.gameserver.services.ai.persistence.PersistedGoalIntent;

class CompanionCreationServiceTest {

	@Test
	void firstCreatePersistsOneBodyAndRepeatReturnsSameIdentity() {
		FakeRepository repository = new FakeRepository();
		FakeGateway gateway = new FakeGateway(repository);
		CompanionCreationService service = new CompanionCreationService(repository, gateway);
		CompanionCreationRequest request = new CompanionCreationRequest(10, "ELYOS", "MALE");

		CompanionCreationResult first = service.create(request, 13, "Test");
		CompanionCreationResult second = service.create(request, 13, "Test");

		assertEquals(CompanionCreationResult.Status.CREATED, first.status());
		assertEquals(CompanionCreationResult.Status.ALREADY_EXISTS, second.status());
		assertEquals(first.body(), second.body());
		assertEquals(1, gateway.createCount);
		assertEquals(first.body().playerId(), repository.states.get(10).binding().companionPlayerId());
	}

	@Test
	void reservedClaimRecoversExistingBodyWithoutCreatingDuplicate() {
		FakeRepository repository = new FakeRepository();
		FakeGateway gateway = new FakeGateway(repository);
		CompanionCreationRequest request = new CompanionCreationRequest(10, "ELYOS", "FEMALE");
		String name = CompanionCreationPolicy.databaseName(10);
		repository.reserveCreation(new CompanionCreationClaim(10, name, 13, "Test", null));
		repository.putBody(new CompanionBodyRecord(77, name, 13, "Test", "ELYOS", "FEMALE", "WARRIOR", false, true));

		CompanionCreationResult result = new CompanionCreationService(repository, gateway).create(request, 13, "Test");

		assertEquals(CompanionCreationResult.Status.RECOVERED, result.status());
		assertEquals(77, result.body().playerId());
		assertEquals(0, gateway.createCount);
	}

	@Test
	void unclaimedNameCollisionAndForeignLegacyBindingFailClosed() {
		FakeRepository repository = new FakeRepository();
		FakeGateway gateway = new FakeGateway(repository);
		CompanionCreationRequest request = new CompanionCreationRequest(10, "ELYOS", "MALE");
		String name = CompanionCreationPolicy.databaseName(10);
		repository.putBody(new CompanionBodyRecord(50, name, 13, "Test", "ELYOS", "MALE", "WARRIOR", false, true));
		CompanionCreationService service = new CompanionCreationService(repository, gateway);
		assertThrows(IllegalStateException.class, () -> service.create(request, 13, "Test"));

		repository.bodies.clear();
		repository.states.put(10, new CompanionPersistentState(
			new CompanionBinding(10, 99, CompanionGoalRestorePolicy.ROLE_PERSONAL_COMPANION), Optional.empty()));
		assertThrows(IllegalStateException.class, () -> service.create(request, 13, "Test"));
		assertEquals(0, gateway.createCount);
	}

	private static final class FakeGateway implements CompanionPlayerCreationGateway {

		private final FakeRepository repository;
		private int createCount;

		private FakeGateway(FakeRepository repository) {
			this.repository = repository;
		}

		@Override
		public CompanionBodyRecord create(CompanionCreationRequest request, String databaseName, int hostAccountId, String hostAccountName) {
			createCount++;
			CompanionBodyRecord body = new CompanionBodyRecord(100 + request.ownerPlayerId(), databaseName, hostAccountId, hostAccountName,
				request.race(), request.gender(), "WARRIOR", false, true);
			repository.putBody(body);
			return body;
		}
	}

	private static final class FakeRepository implements CompanionPersistenceRepository {

		private final Map<Integer, CompanionPersistentState> states = new HashMap<>();
		private final Map<Integer, CompanionCreationClaim> claims = new HashMap<>();
		private final Map<Integer, CompanionBodyRecord> bodies = new HashMap<>();

		@Override
		public Optional<CompanionPersistentState> load(int ownerPlayerId) {
			return Optional.ofNullable(states.get(ownerPlayerId));
		}

		@Override
		public Optional<CompanionCreationClaim> loadCreationClaim(int ownerPlayerId) {
			return Optional.ofNullable(claims.get(ownerPlayerId));
		}

		@Override
		public Optional<CompanionBodyRecord> findCompanionBodyByName(String databaseName) {
			return bodies.values().stream().filter(body -> body.databaseName().equals(databaseName)).findFirst();
		}

		@Override
		public Optional<CompanionBodyRecord> loadCompanionBody(int companionPlayerId) {
			return Optional.ofNullable(bodies.get(companionPlayerId));
		}

		@Override
		public void reserveCreation(CompanionCreationClaim claim) {
			if (claims.putIfAbsent(claim.ownerPlayerId(), claim) != null)
				throw new IllegalStateException("duplicate claim");
		}

		@Override
		public CompanionBinding finalizeCreation(CompanionCreationClaim claim, CompanionBodyRecord body) {
			if (states.values().stream().anyMatch(state -> state.binding().companionPlayerId() == body.playerId()
				&& state.binding().ownerPlayerId() != claim.ownerPlayerId()))
				throw new IllegalStateException("companion already owned");
			claims.put(claim.ownerPlayerId(), new CompanionCreationClaim(claim.ownerPlayerId(), claim.databaseName(), claim.hostAccountId(),
				claim.hostAccountName(), body.playerId()));
			CompanionBinding binding = CompanionCreationService.binding(claim.ownerPlayerId(), body.playerId());
			states.put(claim.ownerPlayerId(), new CompanionPersistentState(binding, Optional.empty()));
			return binding;
		}

		@Override
		public void saveChosenGoal(CompanionBinding binding, PersistedGoalIntent goalIntent) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void saveGoalForExistingBinding(CompanionBinding binding, PersistedGoalIntent goalIntent) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean deleteGoal(int ownerPlayerId, int companionPlayerId) {
			throw new UnsupportedOperationException();
		}

		private void putBody(CompanionBodyRecord body) {
			bodies.put(body.playerId(), body);
		}
	}
}
