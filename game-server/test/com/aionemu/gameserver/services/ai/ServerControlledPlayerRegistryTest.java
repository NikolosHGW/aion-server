package com.aionemu.gameserver.services.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.model.Gender;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.account.Account;
import com.aionemu.gameserver.model.account.PlayerAccountData;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.player.PlayerAppearance;
import com.aionemu.gameserver.model.gameobjects.player.PlayerCommonData;

class ServerControlledPlayerRegistryTest {

	@Test
	void multipleCompanionsCoexistButRouteRemainsSingle() {
		var registry = new ServerControlledPlayerRegistry();
		var first = wrapper(101, SyntheticPlayerRole.COMPANION);
		var second = wrapper(102, SyntheticPlayerRole.COMPANION);
		var route = wrapper(103, SyntheticPlayerRole.ROUTE_SPIKE);
		registry.register(first);
		registry.register(second);
		registry.register(route);
		assertTrue(registry.contains(first));
		assertTrue(registry.contains(second));
		assertSame(route, registry.getActive());
		assertThrows(IllegalStateException.class, () -> registry.register(wrapper(104, SyntheticPlayerRole.ROUTE_SPIKE)));
		assertThrows(IllegalArgumentException.class, () -> registry.getActive(SyntheticPlayerRole.COMPANION));
		registry.unregister(first);
		assertTrue(registry.contains(second));
		assertSame(route, registry.getActive());
	}

	@Test
	void duplicateIdsAndStaleBodiesCannotReplaceOrRemoveCurrentRegistration() {
		var registry = new ServerControlledPlayerRegistry();
		var old = wrapper(101, SyntheticPlayerRole.COMPANION);
		var current = wrapper(101, SyntheticPlayerRole.COMPANION);
		registry.register(old);
		assertThrows(IllegalStateException.class, () -> registry.register(current));
		registry.unregister(old);
		registry.register(current);
		registry.unregister(old);
		assertTrue(registry.contains(current));
		assertTrue(registry.contains(current.getPlayer()));
		assertFalse(registry.contains(old.getPlayer()));
		assertFalse(registry.contains(old));
	}

	static Player player(int id) {
		PlayerCommonData data = new PlayerCommonData(id);
		data.setName("Test" + id);
		data.setPlayerClass(PlayerClass.WARRIOR);
		data.setRace(Race.ELYOS);
		data.setGender(Gender.MALE);
		return new Player(new PlayerAccountData(data, new PlayerAppearance()), new Account(id));
	}

	private static ServerControlledPlayer wrapper(int id, SyntheticPlayerRole role) {
		return new ServerControlledPlayer(player(id), "Test" + id, "[AI]Test" + id, role, new ServerPlayerController() {
			public void tick() {}
			public void stop() {}
		}, () -> true, _ -> {});
	}
}
