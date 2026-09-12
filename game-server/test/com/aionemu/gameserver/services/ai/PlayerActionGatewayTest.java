package com.aionemu.gameserver.services.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.services.ai.PlayerActionGateway.MovementPhase;
import com.aionemu.gameserver.services.ai.PlayerActionGateway.MovementIntent;
import com.aionemu.gameserver.services.ai.PlayerActionGateway.MovementRejectionReason;
import com.aionemu.gameserver.services.ai.PlayerActionGateway.MovementResult;

class PlayerActionGatewayTest {

	@Test
	void rejectionKeepsPhaseAttemptedAndResolvedDestination() {
		MovementResult result = MovementResult.rejected(MovementPhase.START_REVALIDATION, MovementRejectionReason.COLLISION, 1, 2, 3, 4, 5, 6);

		assertFalse(result.success());
		assertEquals(MovementPhase.START_REVALIDATION, result.phase());
		assertEquals(MovementRejectionReason.COLLISION, result.reason());
		assertEquals(1, result.attemptedX());
		assertEquals(3, result.attemptedZ());
		assertEquals(4, result.resolvedX());
		assertEquals(6, result.resolvedZ());
	}

	@Test
	void rejectedMovementCannotUseNoneReason() {
		assertThrows(IllegalArgumentException.class,
			() -> MovementResult.rejected(MovementPhase.INITIAL_CHECK, MovementRejectionReason.NONE, 1, 2, 3));
	}

	@Test
	void breadcrumbChecksItsOwnSegmentWithoutRequiringRemoteOwnerLineOfSight() {
		assertTrue(DefaultPlayerActionGateway.requiresOwnerLineOfSight(MovementIntent.OWNER_OFFSET));
		assertFalse(DefaultPlayerActionGateway.requiresOwnerLineOfSight(MovementIntent.OWNER_BREADCRUMB));
	}
}
