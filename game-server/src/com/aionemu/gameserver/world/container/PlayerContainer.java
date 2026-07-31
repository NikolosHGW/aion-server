package com.aionemu.gameserver.world.container;

import java.util.Collection;
import java.util.Iterator;
import java.util.stream.Collectors;

import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.world.exceptions.DuplicateAionObjectException;

/**
 * Container for storing Players by objectId and name.
 * 
 * @author -Nemesiss-, Neon
 */
public class PlayerContainer implements Iterable<Player> {

	private final IdentityIndexedContainer<Integer, String, Player> players = new IdentityIndexedContainer<>();

	public void add(Player player) {
		Player collision = players.add(player.getObjectId(), player.getName(), player);
		if (collision != null)
			throw new DuplicateAionObjectException(player, collision);
	}

	public void remove(Player player) {
		players.remove(player.getObjectId(), player.getName(), player);
	}

	public Player get(int objectId) {
		return players.getById(objectId);
	}

	public Player get(String name) {
		return players.getByName(name);
	}

	@Override
	public Iterator<Player> iterator() {
		return players.values().iterator();
	}

	public Collection<Player> getAllPlayers() {
		return players.values().stream().filter(p -> p != null).collect(Collectors.toList()); // ensure there are no null values (due to concurrent object removal)
	}

	public void updateCachedPlayerName(String oldName, Player player) {
		players.updateName(oldName, player.getName(), player);
	}
}
