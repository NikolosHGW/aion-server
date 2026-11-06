package com.aionemu.gameserver.services.ai.combat;

import java.util.List;

import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.skillengine.effect.HealInstantEffect;
import com.aionemu.gameserver.skillengine.effect.SkillAttackInstantEffect;
import com.aionemu.gameserver.skillengine.effect.SpellAttackInstantEffect;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.skillengine.model.StigmaType;
import com.aionemu.gameserver.skillengine.properties.TargetRangeAttribute;

/** Explicit first-role catalogue. No arbitrary skills, AoE, summons, DoT or secondary effect chains. */
public final class CompanionSkillPolicy {
	private CompanionSkillPolicy() { }
	public record Approach(float x, float y, float z) { }

	/** A short approach near the owner, never a route or a pursuit across the world. */
	public static Approach approach(double bx, double by, double bz, double tx, double ty, double tz,
			double ox, double oy, double oz) {
		double toOwner = Math.sqrt(Math.pow(tx - ox, 2) + Math.pow(ty - oy, 2) + Math.pow(tz - oz, 2));
		double bodyToOwner = Math.sqrt(Math.pow(bx - ox, 2) + Math.pow(by - oy, 2) + Math.pow(bz - oz, 2));
		double distance = Math.sqrt(Math.pow(tx - bx, 2) + Math.pow(ty - by, 2) + Math.pow(tz - bz, 2));
		if (!Double.isFinite(toOwner + bodyToOwner + distance) || toOwner > 8 || bodyToOwner > 10 || distance <= 1) return null;
		double fraction = Math.min(3, distance - 1) / distance;
		return new Approach((float) (bx + (tx - bx) * fraction), (float) (by + (ty - by) * fraction), (float) (bz + (tz - bz) * fraction));
	}

	public static List<String> attacks(PlayerClass role) {
		return switch (role) {
			case GLADIATOR -> List.of("WA_ROBUSTBLOW", "WA_ROBUSTHIT");
			case CLERIC -> List.of("CL_SMITE");
			default -> List.of();
		};
	}

	public static boolean shouldHeal(PlayerClass role, int hp, int maxHp) {
		return role == PlayerClass.CLERIC && hp > 0 && maxHp > 0 && (long) hp * 100 <= (long) maxHp * 70;
	}

	public static boolean supported(SkillTemplate template, String group) {
		if (template == null || !group.equals(template.getGroup()) || template.isPassive() || template.isCharge()
			|| template.getStigmaType() != StigmaType.NONE || template.getPenaltySkillId() != 0
			|| template.getProperties() == null || template.getProperties().getTargetType() != TargetRangeAttribute.ONLYONE
			|| template.getEffects() == null || template.getEffects().getEffects().size() != 1) return false;
		var effect = template.getEffects().getEffects().getFirst();
		if (effect.getSubEffect() != null) return false;
		return switch (group) {
			case "CL_HEAL" -> effect.getClass() == HealInstantEffect.class;
			case "CL_SMITE" -> effect.getClass() == SpellAttackInstantEffect.class;
			case "WA_ROBUSTHIT", "WA_ROBUSTBLOW" -> effect.getClass() == SkillAttackInstantEffect.class;
			default -> false;
		};
	}
}
