package com.aionemu.gameserver.services.ai.combat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.services.ai.SyntheticPlayerRole;

class PersonalCompanionAttributionRegistryTest {

	private final Object owner = new Object();
	private final Object companion = new Object();
	private final Object citizen = new Object();
	private final PersonalCompanionAttributionRegistry<Object> registry = new PersonalCompanionAttributionRegistry<>();

	@Test
	void exactCompanionMapsToOwnerWhileUnknownSyntheticAndCitizenStaySelf() {
		registry.register(companion, owner, 1, SyntheticPlayerRole.COMPANION);

		assertSame(owner, registry.resolveOrDefault(companion, value -> value));
		assertSame(citizen, registry.resolveOrDefault(citizen, value -> value));
		assertTrue(registry.matches(companion, owner, 1, true));
		assertFalse(registry.matches(companion, new Object(), 1, true));
	}

	@Test
	void ownerAndCompanionDamageSumUnderOneOwnerKeyExactlyOnce() {
		registry.register(companion, owner, 1, SyntheticPlayerRole.COMPANION);
		Map<Object, Integer> damage = new HashMap<>();
		damage.merge(registry.resolveOrDefault(owner, value -> value), 10, Integer::sum);
		damage.merge(registry.resolveOrDefault(companion, value -> value), 20, Integer::sum);

		assertEquals(Map.of(owner, 30), damage);
	}

	@Test
	void ownerGroupProjectionUsesTheOwnerAndNeverCreatesCompanionEntry() {
		registry.register(companion, owner, 1, SyntheticPlayerRole.COMPANION);
		Object ownerGroup = new Object();
		Map<Object, Object> groups = Map.of(owner, ownerGroup);
		Object contributionOwner = registry.resolveOrDefault(companion, value -> value);

		assertSame(ownerGroup, groups.get(contributionOwner));
		assertFalse(groups.containsKey(companion));
	}

	@Test
	void removalMappingSurvivesTransferThenUnregistersIdempotently() {
		registry.register(companion, owner, 1, SyntheticPlayerRole.COMPANION);

		assertTrue(registry.beginRemoval(companion, 1));
		assertFalse(registry.beginRemoval(companion, 1));
		assertSame(owner, registry.resolveOrDefault(companion, value -> value));
		registry.unregister(companion, 1);
		registry.unregister(companion, 1);

		assertSame(companion, registry.resolveOrDefault(companion, value -> value));
		assertFalse(registry.contains(companion));
	}

	@Test
	void roleAndSessionMismatchFailClosed() {
		assertThrows(IllegalArgumentException.class,
			() -> registry.register(companion, owner, 1, SyntheticPlayerRole.ROUTE_SPIKE));
		registry.register(companion, owner, 1, SyntheticPlayerRole.COMPANION);
		registry.unregister(companion, 2);
		assertTrue(registry.contains(companion));
	}
}
