package com.aionemu.gameserver.services.ai;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.aionemu.gameserver.model.gameobjects.player.Player;

public final class ServerControlledPlayerRegistry {

	private final Map<Integer, ServerControlledPlayer> playersByObjectId = new ConcurrentHashMap<>();
	private final Map<SyntheticPlayerRole, ServerControlledPlayer> playersByRole = new EnumMap<>(SyntheticPlayerRole.class);

	public synchronized void register(ServerControlledPlayer player) {
		ServerControlledPlayer objectIdCollision = playersByObjectId.get(player.getObjectId());
		if (objectIdCollision != null)
			throw new IllegalStateException("Synthetic player object ID collision: " + player.getObjectId());
		ServerControlledPlayer roleCollision = playersByRole.get(player.getRole());
		if (roleCollision != null)
			throw new IllegalStateException("Synthetic player role is already active: " + player.getRole());
		playersByObjectId.put(player.getObjectId(), player);
		playersByRole.put(player.getRole(), player);
	}

	public synchronized void unregister(ServerControlledPlayer player) {
		playersByObjectId.computeIfPresent(player.getObjectId(), (_, current) -> current == player ? null : current);
		playersByRole.computeIfPresent(player.getRole(), (_, current) -> current == player ? null : current);
	}

	public ServerControlledPlayer getActive() {
		return getActive(SyntheticPlayerRole.ROUTE_SPIKE);
	}

	public synchronized ServerControlledPlayer getActive(SyntheticPlayerRole role) {
		return playersByRole.get(role);
	}

	public boolean contains(int objectId) {
		return playersByObjectId.containsKey(objectId);
	}

	public boolean contains(ServerControlledPlayer player) {
		return playersByObjectId.get(player.getObjectId()) == player;
	}

	public boolean contains(Player player) {
		ServerControlledPlayer controlled = playersByObjectId.get(player.getObjectId());
		return controlled != null && controlled.getPlayer() == player;
	}

	public synchronized boolean isEmpty() {
		return playersByObjectId.isEmpty();
	}
}
