package com.aionemu.gameserver.services.ai.creation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.services.ai.persistence.CompanionBinding;
import com.aionemu.gameserver.services.ai.persistence.CompanionGoalRestorePolicy;

class CompanionCreationPolicyTest {

	@Test
	void deterministicNamesAreShortLettersOnlyAndOwnerSpecific() {
		String first = CompanionCreationPolicy.databaseName(106714);
		String second = CompanionCreationPolicy.databaseName(106715);

		assertEquals(first, CompanionCreationPolicy.databaseName(106714));
		assertNotEquals(first, second);
		assertTrue(first.matches("[A-Z][a-z]+"));
		assertTrue(("[AI]" + first).length() <= 16);
	}

	@Test
	void exactClaimBindingHostAndBodyAreRequired() {
		CompanionCreationRequest request = new CompanionCreationRequest(10, "ELYOS", "MALE");
		String name = CompanionCreationPolicy.databaseName(10);
		CompanionCreationClaim claim = new CompanionCreationClaim(10, name, 13, "Test", 20);
		CompanionBinding binding = new CompanionBinding(10, 20, CompanionGoalRestorePolicy.ROLE_PERSONAL_COMPANION);
		CompanionBodyRecord body = new CompanionBodyRecord(20, name, 13, "Test", "ELYOS", "MALE", "WARRIOR", false, true);

		assertDoesNotThrow(() -> CompanionCreationPolicy.validateOwnedBody(binding, claim, request, body, 13, "Test"));
		assertThrows(IllegalStateException.class,
			() -> CompanionCreationPolicy.validateOwnedBody(binding, claim, request,
				new CompanionBodyRecord(20, name, 1, "Nikolos", "ELYOS", "MALE", "WARRIOR", false, true), 13, "Test"));
		assertThrows(IllegalStateException.class,
			() -> CompanionCreationPolicy.validateOwnedBody(binding, claim, request,
				new CompanionBodyRecord(20, name, 13, "Test", "ELYOS", "MALE", "WARRIOR", false, false), 13, "Test"));
	}
}
