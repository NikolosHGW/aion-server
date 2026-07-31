package com.aionemu.gameserver.services.ai;

public record CompanionFollowSettings(float startDistance, float stopDistance, float offsetDistance, long destinationUpdateIntervalMs,
									  long blockedRetryIntervalMs) {

	public CompanionFollowSettings {
		if (!Float.isFinite(startDistance) || !Float.isFinite(stopDistance) || !Float.isFinite(offsetDistance))
			throw new IllegalArgumentException("Companion follow distances must be finite");
		if (stopDistance <= 0 || startDistance <= stopDistance)
			throw new IllegalArgumentException("Companion follow start distance must be greater than the positive stop distance");
		if (offsetDistance <= 0 || offsetDistance >= startDistance)
			throw new IllegalArgumentException("Companion follow offset must be positive and less than the start distance");
		if (destinationUpdateIntervalMs < 50 || blockedRetryIntervalMs < destinationUpdateIntervalMs)
			throw new IllegalArgumentException("Companion follow retry intervals are invalid");
	}
}
