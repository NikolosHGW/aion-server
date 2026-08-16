package com.aionemu.gameserver.services.ai.persistence;

import com.aionemu.gameserver.services.ai.persistence.CompanionGoalRestorePolicy.RestoreResult;

public final class CompanionPersistenceRuntime {

	private boolean persistedGoalPresent;
	private RestoreResult lastResult = RestoreResult.NOT_ATTEMPTED;
	private int planVersion;

	public synchronized void update(boolean persistedGoalPresent, RestoreResult result, int planVersion) {
		this.persistedGoalPresent = persistedGoalPresent;
		this.lastResult = result;
		this.planVersion = planVersion;
	}

	public synchronized String status(boolean enabled) {
		return "goalPersistenceEnabled=" + enabled + ", persistedGoalPresent=" + persistedGoalPresent + ", goalRestoreResult=" + lastResult
			+ ", persistedPlanVersion=" + (planVersion == 0 ? "none" : planVersion);
	}
}
