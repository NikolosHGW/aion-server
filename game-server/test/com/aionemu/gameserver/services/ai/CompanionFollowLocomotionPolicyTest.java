package com.aionemu.gameserver.services.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CompanionFollowLocomotionPolicyTest {

	@Test
	void ownerSlowerThanNativeDoesNotSlowCompanion() {
		var decision = decide(7, 6, 8);

		assertEquals(7, decision.effectiveSpeed());
		assertFalse(decision.catchUpActive());
		assertFalse(decision.requiresOverride(7));
	}

	@Test
	void legalFastOwnerIsAlwaysMatched() {
		var normal = decide(6, 9.4f, 8);
		var aboveCatchUpCap = decide(6, 13, 20);

		assertEquals(9.4f, normal.effectiveSpeed());
		assertEquals(13, aboveCatchUpCap.effectiveSpeed());
		assertTrue(normal.requiresOverride(6));
	}

	@Test
	void significantSeparationAddsBoundedCatchUp() {
		var decision = decide(6, 7.8f, CompanionFollowLocomotionPolicy.SIGNIFICANT_SEPARATION);

		assertEquals(8.8f, decision.effectiveSpeed(), 0.0001);
		assertTrue(decision.catchUpActive());
		assertTrue(decision.effectiveSpeed() > 7.8f);
		assertTrue(decision.effectiveSpeed() <= CompanionFollowLocomotionPolicy.CATCH_UP_HARD_CAP);
	}

	@Test
	void activeCatchUpUsesStartDistanceAsExitHysteresis() {
		var stillActive = CompanionFollowLocomotionPolicy.decide(6, 7.8f, 8, true, true, false, true, 6);
		var returnedToNormalRange = CompanionFollowLocomotionPolicy.decide(6, 7.8f, 6, true, true, false, true, 6);

		assertTrue(stillActive.catchUpActive());
		assertFalse(returnedToNormalRange.catchUpActive());
		assertEquals(7.8f, returnedToNormalRange.effectiveSpeed());
	}

	@Test
	void catchUpMathematicallyReducesSeparation() {
		var decision = decide(6, 7.8f, 30);
		float oneSecondSeparationDelta = 7.8f - decision.effectiveSpeed();

		assertTrue(oneSecondSeparationDelta < 0);
	}

	@Test
	void nativeRestrictionsAndCombatSuppressOverride() {
		var unsupported = CompanionFollowLocomotionPolicy.decide(6, 8, 30, false, true, false, false, 6);
		var prohibited = CompanionFollowLocomotionPolicy.decide(6, 8, 30, true, false, false, false, 6);
		var combat = CompanionFollowLocomotionPolicy.decide(6, 8, 30, true, true, true, false, 6);

		assertEquals("UNSUPPORTED_MOVEMENT", unsupported.suppression());
		assertEquals("MOVEMENT_NOT_ALLOWED", prohibited.suppression());
		assertEquals("COMBAT", combat.suppression());
		assertEquals(6, unsupported.effectiveSpeed());
		assertEquals(6, prohibited.effectiveSpeed());
		assertEquals(6, combat.effectiveSpeed());
	}

	private CompanionFollowLocomotionPolicy.Decision decide(float nativeSpeed, float ownerSpeed, double distance) {
		return CompanionFollowLocomotionPolicy.decide(nativeSpeed, ownerSpeed, distance, true, true, false, false, 6);
	}
}
