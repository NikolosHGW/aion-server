package com.aionemu.gameserver.services.ai.combat;

import java.util.Set;

public final class CombatPreflightPolicy {

	private CombatPreflightPolicy() {
	}

	public static CombatStatus evaluate(Set<CombatPreflightViolation> violations) {
		for (CombatPreflightViolation violation : CombatPreflightViolation.values()) {
			if (violations.contains(violation))
				return map(violation);
		}
		return CombatStatus.HIT_STARTED;
	}

	private static CombatStatus map(CombatPreflightViolation violation) {
		return switch (violation) {
			case RUNTIME_DISABLED -> CombatStatus.RUNTIME_DISABLED;
			case REMOVING -> CombatStatus.REMOVING;
			case ASSIST_DISABLED -> CombatStatus.ASSIST_DISABLED;
			case SESSION_MISMATCH -> CombatStatus.SESSION_MISMATCH;
			case ROLE_MISMATCH -> CombatStatus.ROLE_MISMATCH;
			case EVENT_EXPIRED -> CombatStatus.EVENT_EXPIRED;
			case TARGET_NOT_NPC, TARGET_NOT_ALLOWED, TARGET_UNSUPPORTED -> CombatStatus.TARGET_NOT_ALLOWED;
			case TARGET_UNSPAWNED -> CombatStatus.TARGET_UNSPAWNED;
			case TARGET_DEAD -> CombatStatus.TARGET_DEAD;
			case TARGET_INVULNERABLE -> CombatStatus.TARGET_INVULNERABLE;
			case TARGET_NOT_HOSTILE -> CombatStatus.TARGET_NOT_HOSTILE;
			case MAP_OR_INSTANCE_MISMATCH -> CombatStatus.MAP_OR_INSTANCE_MISMATCH;
			case TARGET_NOT_KNOWN -> CombatStatus.TARGET_NOT_KNOWN;
			case TARGET_NOT_VISIBLE -> CombatStatus.TARGET_NOT_VISIBLE;
			case OUT_OF_RANGE -> CombatStatus.OUT_OF_RANGE;
			case NO_LINE_OF_SIGHT -> CombatStatus.NO_LINE_OF_SIGHT;
			case COMPANION_DEAD -> CombatStatus.COMPANION_DEAD;
			case UNSAFE_EQUIPMENT -> CombatStatus.UNSAFE_EQUIPMENT;
			case COOLDOWN -> CombatStatus.COOLDOWN;
		};
	}
}
