package com.aionemu.gameserver.services.ai.items;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.templates.item.actions.SkillUseAction;
import com.aionemu.gameserver.skillengine.effect.HealInstantEffect;
import com.aionemu.gameserver.skillengine.effect.MPHealInstantEffect;
import com.aionemu.gameserver.skillengine.effect.ProcHealInstantEffect;
import com.aionemu.gameserver.skillengine.effect.ProcMPHealInstantEffect;
import com.aionemu.gameserver.skillengine.properties.TargetRangeAttribute;

/** Deliberately small item catalogue; unknown scripts and delayed effects fail closed. */
public final class CompanionItemPolicy {
	private CompanionItemPolicy() { }

	public static boolean transferable(Item item) {
		return item != null && item.getItemCount() > 0 && !item.isEquipped() && item.isTradeable()
			&& !item.getItemTemplate().isKinah() && item.getItemTemplate().getExtraInventoryId() < 1
			&& item.getExpireTime() == 0 && item.getPackCount() == 0 && !item.isSoulBound();
	}

	public static SkillUseAction potion(Item item) {
		if (item == null || item.getItemCount() <= 0 || item.isEquipped() || item.getActivationCount() != 1
			|| item.getItemTemplate().getActions() == null) return null;
		var actions = item.getItemTemplate().getActions().getItemActions();
		if (actions.size() != 1 || !(actions.getFirst() instanceof SkillUseAction use)) return null;
		var skill = DataManager.SKILL_DATA.getSkillTemplate(use.getSkillId());
		if (skill == null || skill.getDuration() != 0 || skill.getPenaltySkillId() != 0 || skill.getEffects() == null
			|| skill.getProperties() == null || skill.getProperties().getTargetType() != TargetRangeAttribute.ONLYONE
			|| skill.getEffects().getEffects().isEmpty()) return null;
		for (var effect : skill.getEffects().getEffects())
			if (effect.getSubEffect() != null || effect.getValue() < 0
				|| effect.getClass() != HealInstantEffect.class && effect.getClass() != MPHealInstantEffect.class
					&& effect.getClass() != ProcHealInstantEffect.class && effect.getClass() != ProcMPHealInstantEffect.class) return null;
		return use;
	}
}
