package com.aionemu.gameserver.services.ai;

interface CompanionContext {

	int ownerObjectId();

	String ownerName();

	boolean ownerConnected();

	boolean ownerInWorld();

	boolean ownerSpawned();

	boolean ownerDead();

	int ownerMapId();

	int ownerInstanceId();

	float ownerX();

	float ownerY();

	float ownerZ();

	byte ownerHeading();

	boolean companionInWorld();

	boolean companionSpawned();

	int companionMapId();

	int companionInstanceId();

	double distanceToOwner();
}
