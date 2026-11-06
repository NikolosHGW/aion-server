package com.aionemu.gameserver.services.ai.combat;

/** Optional continuing activity; the historical single-hit action contract stays unchanged. */
public interface CombatActivityGateway extends CombatActionGateway {
	void tick();
	boolean holdsMovement();
	String diagnostics();
}
