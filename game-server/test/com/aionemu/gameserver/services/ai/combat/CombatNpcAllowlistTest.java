package com.aionemu.gameserver.services.ai.combat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import org.junit.jupiter.api.Test;

class CombatNpcAllowlistTest {

	@Test
	void firstSpikeAcceptsOnlyJuvenileSparkie() {
		assertEquals(Set.of(210115), CombatNpcAllowlist.parse("210115"));
		assertDoesNotThrow(() -> CombatNpcAllowlist.requireFirstSpikeOnly("210115"));
		assertThrows(IllegalStateException.class, () -> CombatNpcAllowlist.requireFirstSpikeOnly(""));
		assertThrows(IllegalStateException.class, () -> CombatNpcAllowlist.requireFirstSpikeOnly("210115,210116"));
		assertThrows(IllegalArgumentException.class, () -> CombatNpcAllowlist.parse("not-an-id"));
	}
}
