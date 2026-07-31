package com.aionemu.gameserver.world.container;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class IdentityIndexedContainerTest {

	@Test
	void nameCollisionRollsBackOnlyNewIdIndex() {
		IdentityIndexedContainer<Integer, String, Object> container = new IdentityIndexedContainer<>();
		Object realPlayer = new Object();
		Object collidingRuntimePlayer = new Object();
		assertNull(container.add(1, "Real", realPlayer));

		assertSame(realPlayer, container.add(2, "Real", collidingRuntimePlayer));

		assertSame(realPlayer, container.getById(1));
		assertSame(realPlayer, container.getByName("Real"));
		assertNull(container.getById(2));
	}

	@Test
	void identitySafeRemoveDoesNotDeleteDifferentValueWithSameKeys() {
		IdentityIndexedContainer<Integer, String, Object> container = new IdentityIndexedContainer<>();
		Object realPlayer = new Object();
		Object collidingRuntimePlayer = new Object();
		container.add(1, "Real", realPlayer);

		container.remove(1, "Real", collidingRuntimePlayer);

		assertSame(realPlayer, container.getById(1));
		assertSame(realPlayer, container.getByName("Real"));
	}

	@Test
	void unchangedNameUpdateKeepsTheNameIndex() {
		IdentityIndexedContainer<Integer, String, Object> container = new IdentityIndexedContainer<>();
		Object player = new Object();
		container.add(1, "Same", player);

		container.updateName("Same", "Same", player);

		assertSame(player, container.getByName("Same"));
	}
}
