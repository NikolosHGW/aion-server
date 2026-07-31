package com.aionemu.gameserver.services.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CompanionPreflightTest {

	@Test
	void flagsDisabledRejectSummonWithoutWorldMutation() {
		assertThrows(IllegalStateException.class, () -> CompanionPreflight.requireEnabled(false, true));
		assertThrows(IllegalStateException.class, () -> CompanionPreflight.requireEnabled(true, false));
		assertDoesNotThrow(() -> CompanionPreflight.requireEnabled(true, true));
	}

	@Test
	void ownerMustBeAConnectedRealPlayerInTheWorld() {
		assertThrows(IllegalStateException.class, () -> CompanionPreflight.validateOwner(false, true, true, false));
		assertThrows(IllegalStateException.class, () -> CompanionPreflight.validateOwner(true, false, true, false));
		assertThrows(IllegalStateException.class, () -> CompanionPreflight.validateOwner(true, true, false, false));
		assertThrows(IllegalStateException.class, () -> CompanionPreflight.validateOwner(true, true, true, true));
		assertDoesNotThrow(() -> CompanionPreflight.validateOwner(true, true, true, false));
	}

	@Test
	void followSettingsRequireHysteresisAndLimitedRetry() {
		assertThrows(IllegalArgumentException.class, () -> new CompanionFollowSettings(3, 3, 1, 400, 1000));
		assertThrows(IllegalArgumentException.class, () -> new CompanionFollowSettings(2, 3, 1, 400, 1000));
		assertThrows(IllegalArgumentException.class, () -> new CompanionFollowSettings(6, 3, 2, 400, 200));
		assertDoesNotThrow(() -> new CompanionFollowSettings(6, 3, 2, 400, 1000));
	}

	@Test
	void spawnSettingsMustBePositiveAndFinite() {
		assertThrows(IllegalArgumentException.class, () -> CompanionPreflight.validateSpawnSettings(0, 0.75f));
		assertThrows(IllegalArgumentException.class, () -> CompanionPreflight.validateSpawnSettings(2, Float.NaN));
		assertDoesNotThrow(() -> CompanionPreflight.validateSpawnSettings(2, 0.75f));
	}
}
