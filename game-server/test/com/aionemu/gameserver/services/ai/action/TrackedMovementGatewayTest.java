package com.aionemu.gameserver.services.ai.action;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.aionemu.gameserver.services.ai.PlayerActionGateway;

class TrackedMovementGatewayTest {
	static class NativeMovement implements PlayerActionGateway {
		int starts, stops;
		boolean moving, reject;
		Runnable onStart = () -> {};
		public MovementResult checkMovement(MovementIntent intent, float x, float y, float z) {
			return MovementResult.success(MovementPhase.INITIAL_CHECK, x,y,z,x,y,z);
		}
		public MovementResult startMove(MovementIntent intent, MovementResult destination, float speed) {
			starts++;
			if (reject) return MovementResult.rejected(MovementPhase.START_REVALIDATION, MovementRejectionReason.COLLISION, 1,2,3);
			moving = true; onStart.run(); return destination;
		}
		public void stopMove() { stops++; moving = false; }
	}
	@Test void synchronousCloseDuringNativeStartStillStopsMovement() {
		var nativeMovement = new NativeMovement();
		var gateway = new TrackedMovementGateway(nativeMovement, () -> true, () -> 100, () -> 0, () -> 0, () -> 0,
			() -> nativeMovement.moving);
		nativeMovement.onStart = gateway::close;
		gateway.startMove(gateway.checkMovement(PlayerActionGateway.MovementIntent.OWNER_OFFSET, 10,0,0));
		assertFalse(nativeMovement.moving);
		assertEquals(1, nativeMovement.stops);
		assertTrue(gateway.status().contains("CANCELLED"));
	}
	@Test void retargetDoesNotInjectStopPacketsAndStopCancelsNativeMovement() {
		var nativeMovement = new NativeMovement();
		var gateway = new TrackedMovementGateway(nativeMovement, () -> true, () -> 100, () -> 0, () -> 0, () -> 0,
			() -> nativeMovement.moving);
		var destination = gateway.checkMovement(PlayerActionGateway.MovementIntent.OWNER_OFFSET, 10,0,0);
		assertTrue(gateway.startMove(destination).success());
		assertTrue(gateway.startMove(destination).success());
		assertEquals(2, nativeMovement.starts); assertEquals(0, nativeMovement.stops);
		gateway.stopMove(); assertEquals(1, nativeMovement.stops);
		assertTrue(gateway.status().contains("CANCELLED"));
	}
	@Test void successRequiresArrivalAndRejectionStopsOldMove() {
		var nativeMovement = new NativeMovement();
		double[] position = {0};
		var gateway = new TrackedMovementGateway(nativeMovement, () -> true, () -> 100, () -> position[0], () -> 0, () -> 0,
			() -> nativeMovement.moving);
		var destination = gateway.checkMovement(PlayerActionGateway.MovementIntent.OWNER_OFFSET, 10,0,0);
		gateway.startMove(destination); assertTrue(gateway.status().contains("RUNNING"));
		position[0] = 10; nativeMovement.moving = false; gateway.poll();
		assertTrue(gateway.status().contains("SUCCEEDED"));
		nativeMovement.reject = true;
		assertFalse(gateway.startMove(destination).success());
		assertTrue(gateway.status().contains("REJECTED")); assertEquals(1, nativeMovement.stops);
	}
}
