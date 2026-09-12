package com.aionemu.gameserver.services.ai;

public interface PlayerActionGateway {

	default MovementResult checkMovement(float x, float y, float z) {
		return checkMovement(MovementIntent.OWNER_OFFSET, x, y, z);
	}

	MovementResult checkMovement(MovementIntent intent, float x, float y, float z);

	default MovementResult startMove(MovementResult movement) {
		return startMove(MovementIntent.OWNER_OFFSET, movement, Float.NaN);
	}

	MovementResult startMove(MovementIntent intent, MovementResult movement, float effectiveMovementSpeed);

	void stopMove();

	default void clearServerControlledMovementSpeed() {
	}

	enum MovementIntent {
		OWNER_OFFSET,
		OWNER_BREADCRUMB
	}

	enum MovementPhase {
		POLICY,
		INITIAL_CHECK,
		START_REVALIDATION,
		START_EXECUTION
	}

	enum MovementRejectionReason {
		NONE,
		OWNER_DEAD,
		DISTANCE_NOT_FINITE,
		DESTINATION_NOT_FINITE,
		GEODATA_OR_LOS_DISABLED,
		COMPANION_NOT_IN_WORLD,
		MOVEMENT_NOT_ALLOWED,
		VISIBILITY_TARGET_MAP_OR_INSTANCE_MISMATCH,
		OWNER_LINE_OF_SIGHT,
		DESTINATION_HAS_NO_GEODATA,
		DESTINATION_OUTSIDE_WORLD_REGION,
		COLLISION,
		LINE_OF_SIGHT,
		MOVE_CONTROLLER_REJECTED,
		LOCOMOTION_OVERRIDE_REJECTED
	}

	record MovementResult(boolean success, MovementPhase phase, MovementRejectionReason reason, float attemptedX, float attemptedY,
			float attemptedZ, float resolvedX, float resolvedY, float resolvedZ) {

		public static MovementResult success(MovementPhase phase, float attemptedX, float attemptedY, float attemptedZ, float resolvedX,
				float resolvedY, float resolvedZ) {
			return new MovementResult(true, phase, MovementRejectionReason.NONE, attemptedX, attemptedY, attemptedZ, resolvedX, resolvedY, resolvedZ);
		}

		public static MovementResult rejected(MovementPhase phase, MovementRejectionReason reason, float attemptedX, float attemptedY,
				float attemptedZ) {
			return rejected(phase, reason, attemptedX, attemptedY, attemptedZ, Float.NaN, Float.NaN, Float.NaN);
		}

		public static MovementResult rejected(MovementPhase phase, MovementRejectionReason reason, float attemptedX, float attemptedY,
				float attemptedZ, float resolvedX, float resolvedY, float resolvedZ) {
			if (reason == MovementRejectionReason.NONE)
				throw new IllegalArgumentException("Rejected movement requires a reason");
			return new MovementResult(false, phase, reason, attemptedX, attemptedY, attemptedZ, resolvedX, resolvedY, resolvedZ);
		}
	}
}
