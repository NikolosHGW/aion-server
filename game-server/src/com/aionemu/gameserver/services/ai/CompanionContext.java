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

	float ownerEffectiveGroundSpeed();

	boolean companionInWorld();

	boolean companionSpawned();

	int companionMapId();

	int companionInstanceId();

	float companionX();

	float companionY();

	float companionZ();

	float companionNativeGroundSpeed();

	boolean supportedGroundMovement();

	boolean companionMovementAllowed();

	double distanceToOwner();
}
