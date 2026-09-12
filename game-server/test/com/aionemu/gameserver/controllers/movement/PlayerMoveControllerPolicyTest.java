package com.aionemu.gameserver.controllers.movement;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PlayerMoveControllerPolicyTest {

	@Test
	void connectedPlayerCannotReceiveServerControlledSpeedOverride() {
		assertFalse(PlayerMoveController.isServerControlledMovementSpeedAllowed(true, 8.8f));
	}

	@Test
	void disconnectedServerControlledPlayerRequiresFinitePositiveSpeed() {
		assertTrue(PlayerMoveController.isServerControlledMovementSpeedAllowed(false, 8.8f));
		assertFalse(PlayerMoveController.isServerControlledMovementSpeedAllowed(false, Float.NaN));
		assertFalse(PlayerMoveController.isServerControlledMovementSpeedAllowed(false, Float.POSITIVE_INFINITY));
		assertFalse(PlayerMoveController.isServerControlledMovementSpeedAllowed(false, 0));
	}
}
