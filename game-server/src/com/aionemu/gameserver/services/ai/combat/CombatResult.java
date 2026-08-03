package com.aionemu.gameserver.services.ai.combat;

public record CombatResult(CombatStatus status, String reason, long eventId, int targetObjectId, int targetTemplateId) {

	public static CombatResult of(CombatStatus status, String reason, OwnerAttackSignal signal) {
		return new CombatResult(status, reason, signal == null ? 0 : signal.eventId(), signal == null ? 0 : signal.targetObjectId(),
			signal == null ? 0 : signal.targetTemplateId());
	}

	public boolean hitStarted() {
		return status == CombatStatus.HIT_STARTED;
	}
}
