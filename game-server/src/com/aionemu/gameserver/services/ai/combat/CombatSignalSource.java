package com.aionemu.gameserver.services.ai.combat;

public interface CombatSignalSource {

	boolean enable();

	boolean disable();

	boolean isEnabled();

	boolean isObserverAttached();

	OwnerAttackSignal poll();

	void clear();
}
