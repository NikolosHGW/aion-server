package com.aionemu.gameserver.services.ai;

import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.world.World;

final class PlayerCompanionContext implements CompanionContext {

	private final Player owner;
	private final Player companion;

	PlayerCompanionContext(Player owner, Player companion) {
		this.owner = owner;
		this.companion = companion;
	}

	@Override
	public int ownerObjectId() {
		return owner.getObjectId();
	}

	@Override
	public String ownerName() {
		return owner.getName();
	}

	@Override
	public boolean ownerConnected() {
		return owner.getClientConnection() != null;
	}

	@Override
	public boolean ownerInWorld() {
		return World.getInstance().findVisibleObject(owner.getObjectId()) == owner;
	}

	@Override
	public boolean ownerSpawned() {
		return owner.isSpawned();
	}

	@Override
	public boolean ownerDead() {
		return owner.isDead();
	}

	@Override
	public int ownerMapId() {
		return owner.getWorldId();
	}

	@Override
	public int ownerInstanceId() {
		return owner.getInstanceId();
	}

	@Override
	public float ownerX() {
		return owner.getX();
	}

	@Override
	public float ownerY() {
		return owner.getY();
	}

	@Override
	public float ownerZ() {
		return owner.getZ();
	}

	@Override
	public byte ownerHeading() {
		return owner.getHeading();
	}

	@Override
	public boolean companionInWorld() {
		return World.getInstance().findVisibleObject(companion.getObjectId()) == companion;
	}

	@Override
	public boolean companionSpawned() {
		return companion.isSpawned();
	}

	@Override
	public int companionMapId() {
		return companion.getWorldId();
	}

	@Override
	public int companionInstanceId() {
		return companion.getInstanceId();
	}

	@Override
	public double distanceToOwner() {
		return PositionUtil.getDistance(companion, owner);
	}
}
