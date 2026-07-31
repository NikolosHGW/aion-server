package com.aionemu.gameserver.services.ai;

public interface PlayerActionGateway {

	MovementCheck checkMovement(float x, float y, float z);

	boolean startMove(MovementCheck movement);

	void stopMove();

	record MovementCheck(boolean allowed, float x, float y, float z, String reason) {

		public static MovementCheck allowed(float x, float y, float z) {
			return new MovementCheck(true, x, y, z, "");
		}

		public static MovementCheck blocked(String reason) {
			return new MovementCheck(false, Float.NaN, Float.NaN, Float.NaN, reason);
		}
	}
}
