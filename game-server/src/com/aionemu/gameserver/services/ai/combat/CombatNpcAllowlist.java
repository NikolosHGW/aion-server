package com.aionemu.gameserver.services.ai.combat;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public final class CombatNpcAllowlist {

	public static final int FIRST_SPIKE_NPC_ID = 210115;

	private CombatNpcAllowlist() {
	}

	public static Set<Integer> parse(String configuredIds) {
		if (configuredIds == null || configuredIds.isBlank())
			return Set.of();
		try {
			return Arrays.stream(configuredIds.split(",")).map(String::trim).filter(value -> !value.isEmpty()).map(Integer::parseInt)
				.filter(value -> value > 0).collect(Collectors.toUnmodifiableSet());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid companion combat NPC allowlist", e);
		}
	}

	public static void requireFirstSpikeOnly(String configuredIds) {
		Set<Integer> ids = parse(configuredIds);
		if (!ids.equals(Set.of(FIRST_SPIKE_NPC_ID)))
			throw new IllegalStateException("Stage 2C requires the exact NPC allowlist: " + FIRST_SPIKE_NPC_ID);
	}
}
