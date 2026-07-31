package com.aionemu.gameserver.services.ai;

interface ScheduledSyntheticPlayer {

	int getObjectId();

	boolean isActive();

	boolean isFeatureEnabled();

	void tick();

	void requestRemoval(String reason);
}
