package com.aionemu.gameserver.services.ai.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumSet;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CombatPreflightPolicyTest {

	@Test
	void noViolationAllowsAHit() {
		assertEquals(CombatStatus.HIT_STARTED, CombatPreflightPolicy.evaluate(EnumSet.noneOf(CombatPreflightViolation.class)));
	}

	@Test
	void everyFailClosedCheckHasAStructuredOutcome() {
		Map<CombatPreflightViolation, CombatStatus> expected = Map.ofEntries(
			Map.entry(CombatPreflightViolation.RUNTIME_DISABLED, CombatStatus.RUNTIME_DISABLED),
			Map.entry(CombatPreflightViolation.REMOVING, CombatStatus.REMOVING),
			Map.entry(CombatPreflightViolation.ASSIST_DISABLED, CombatStatus.ASSIST_DISABLED),
			Map.entry(CombatPreflightViolation.SESSION_MISMATCH, CombatStatus.SESSION_MISMATCH),
			Map.entry(CombatPreflightViolation.ROLE_MISMATCH, CombatStatus.ROLE_MISMATCH),
			Map.entry(CombatPreflightViolation.EVENT_EXPIRED, CombatStatus.EVENT_EXPIRED),
			Map.entry(CombatPreflightViolation.TARGET_NOT_NPC, CombatStatus.TARGET_NOT_ALLOWED),
			Map.entry(CombatPreflightViolation.TARGET_NOT_ALLOWED, CombatStatus.TARGET_NOT_ALLOWED),
			Map.entry(CombatPreflightViolation.TARGET_UNSUPPORTED, CombatStatus.TARGET_NOT_ALLOWED),
			Map.entry(CombatPreflightViolation.TARGET_UNSPAWNED, CombatStatus.TARGET_UNSPAWNED),
			Map.entry(CombatPreflightViolation.TARGET_DEAD, CombatStatus.TARGET_DEAD),
			Map.entry(CombatPreflightViolation.TARGET_INVULNERABLE, CombatStatus.TARGET_INVULNERABLE),
			Map.entry(CombatPreflightViolation.TARGET_NOT_HOSTILE, CombatStatus.TARGET_NOT_HOSTILE),
			Map.entry(CombatPreflightViolation.MAP_OR_INSTANCE_MISMATCH, CombatStatus.MAP_OR_INSTANCE_MISMATCH),
			Map.entry(CombatPreflightViolation.TARGET_NOT_KNOWN, CombatStatus.TARGET_NOT_KNOWN),
			Map.entry(CombatPreflightViolation.TARGET_NOT_VISIBLE, CombatStatus.TARGET_NOT_VISIBLE),
			Map.entry(CombatPreflightViolation.OUT_OF_RANGE, CombatStatus.OUT_OF_RANGE),
			Map.entry(CombatPreflightViolation.NO_LINE_OF_SIGHT, CombatStatus.NO_LINE_OF_SIGHT),
			Map.entry(CombatPreflightViolation.COMPANION_DEAD, CombatStatus.COMPANION_DEAD),
			Map.entry(CombatPreflightViolation.UNSAFE_EQUIPMENT, CombatStatus.UNSAFE_EQUIPMENT),
			Map.entry(CombatPreflightViolation.COOLDOWN, CombatStatus.COOLDOWN));

		for (CombatPreflightViolation violation : CombatPreflightViolation.values())
			assertEquals(expected.get(violation), CombatPreflightPolicy.evaluate(EnumSet.of(violation)), violation.name());
	}

	@Test
	void safetyOrderingPrefersRuntimeAndLifecycleFailures() {
		assertEquals(CombatStatus.RUNTIME_DISABLED,
			CombatPreflightPolicy.evaluate(EnumSet.of(CombatPreflightViolation.RUNTIME_DISABLED, CombatPreflightViolation.OUT_OF_RANGE)));
		assertEquals(CombatStatus.REMOVING,
			CombatPreflightPolicy.evaluate(EnumSet.of(CombatPreflightViolation.REMOVING, CombatPreflightViolation.TARGET_NOT_VISIBLE)));
	}
}
