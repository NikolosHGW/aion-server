package com.aionemu.gameserver.taskmanager.tasks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.taskmanager.AbstractPeriodicTaskManager;

/**
 * @author ATracer
 */
public class PlayerMoveTaskManager extends AbstractPeriodicTaskManager {

	private final Map<Integer, Creature> movingPlayers = new ConcurrentHashMap<>();

	private PlayerMoveTaskManager() {
		super(200);
	}

	public boolean addPlayer(Creature player) {
		Creature existing = movingPlayers.putIfAbsent(player.getObjectId(), player);
		return existing == null || existing == player;
	}

	public void removePlayer(Creature player) {
		movingPlayers.computeIfPresent(player.getObjectId(), (_, current) -> current == player ? null : current);
	}

	public boolean contains(Creature player) {
		return movingPlayers.get(player.getObjectId()) == player;
	}

	public boolean containsObjectId(int objectId) {
		return movingPlayers.containsKey(objectId);
	}

	@Override
	public void run() {
		for (Creature player : movingPlayers.values()) {
			if (player.isSpawned())
				player.getMoveController().moveToDestination();
			else
				removePlayer(player);
		}
	}

	public static final PlayerMoveTaskManager getInstance() {
		return SingletonHolder.INSTANCE;
	}

	private static final class SingletonHolder {

		private static final PlayerMoveTaskManager INSTANCE = new PlayerMoveTaskManager();
	}
}
