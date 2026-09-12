package com.aionemu.gameserver.services.ai;

final class CompanionFollowLocomotionPolicy {

	static final float SIGNIFICANT_SEPARATION = 12;
	static final float CATCH_UP_BONUS = 1;
	static final float CATCH_UP_HARD_CAP = 12;

	private CompanionFollowLocomotionPolicy() {
	}

	static Decision decide(float nativeSpeed, float ownerSpeed, double distance, boolean supportedGroundMovement,
			boolean movementAllowed, boolean combatSuppressed, boolean catchUpWasActive, double catchUpExitDistance) {
		if (!Float.isFinite(nativeSpeed) || nativeSpeed <= 0)
			return new Decision(Float.NaN, false, "INVALID_NATIVE_SPEED");
		if (!supportedGroundMovement)
			return new Decision(nativeSpeed, false, "UNSUPPORTED_MOVEMENT");
		if (!movementAllowed)
			return new Decision(nativeSpeed, false, "MOVEMENT_NOT_ALLOWED");
		if (combatSuppressed)
			return new Decision(nativeSpeed, false, "COMBAT");
		if (!Float.isFinite(ownerSpeed) || ownerSpeed <= 0)
			return new Decision(nativeSpeed, false, "INVALID_OWNER_SPEED");

		float baseline = Math.max(nativeSpeed, ownerSpeed);
		float effective = baseline;
		boolean catchUpDistance = Double.isFinite(distance)
			&& (distance >= SIGNIFICANT_SEPARATION || catchUpWasActive && distance > catchUpExitDistance);
		if (catchUpDistance)
			effective = Math.max(baseline, Math.min(CATCH_UP_HARD_CAP, ownerSpeed + CATCH_UP_BONUS));
		return new Decision(effective, effective > baseline, "NONE");
	}

	record Decision(float effectiveSpeed, boolean catchUpActive, String suppression) {
		boolean requiresOverride(float nativeSpeed) {
			return Float.isFinite(effectiveSpeed) && effectiveSpeed > nativeSpeed;
		}
	}
}
