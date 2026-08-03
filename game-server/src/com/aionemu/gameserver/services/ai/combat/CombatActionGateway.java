package com.aionemu.gameserver.services.ai.combat;

public interface CombatActionGateway {

	CombatResult basicHitOnce(OwnerAttackSignal signal);

	void stop(String reason);
}
