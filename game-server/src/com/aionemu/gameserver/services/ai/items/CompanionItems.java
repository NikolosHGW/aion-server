package com.aionemu.gameserver.services.ai.items;

import java.util.List;
import java.util.function.BooleanSupplier;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.network.aion.serverpackets.SM_UPDATE_PLAYER_APPEARANCE;
import com.aionemu.gameserver.restrictions.PlayerRestrictions;
import com.aionemu.gameserver.skillengine.model.Effect;
import com.aionemu.gameserver.skillengine.model.Skill;
import com.aionemu.gameserver.skillengine.properties.Properties.CastState;
import com.aionemu.gameserver.utils.PacketSendUtility;

/** Invoked under the owner's incarnation lock. Native equipment/costs/stats remain authoritative. */
public final class CompanionItems {
	private CompanionItems() { }

	public static boolean equip(Player body, int itemId, long slot) {
		if (!PlayerRestrictions.canChangeEquip(body)) return false;
		Item item = slot == 0 ? body.getEquipment().getEquippedItemByObjId(itemId) : body.getInventory().getItemByObjId(itemId);
		if (item == null || item.getItemTemplate().isStigma()
			|| item.getItemTemplate().isSoulBound() && !item.isSoulBound()) return false;
		Item result = slot == 0 ? body.getEquipment().unEquipItem(itemId) : body.getEquipment().equipItem(itemId, slot);
		if (result != null) PacketSendUtility.broadcastPacket(body,
			new SM_UPDATE_PLAYER_APPEARANCE(body.getObjectId(), body.getEquipment().getEquippedForAppearance()), true);
		return result != null;
	}

	public static boolean use(Player body, int itemId, BooleanSupplier authorized) {
		Item item = body.getInventory().getItemByObjId(itemId);
		var use = CompanionItemPolicy.potion(item);
		if (use == null || body.isOnline() || body.getClientConnection() != null || !authorized.getAsBoolean()
			|| !PlayerRestrictions.canUseItemState(body, item)) return false;
		var previousTarget = body.getTarget();
		try {
			body.setTarget(body);
			if (!use.canAct(body, item, null)) return false;
			Skill skill = new Skill(DataManager.SKILL_DATA.getSkillTemplate(use.getSkillId()), body, use.getLevel(), body, item.getItemTemplate()) {
				@Override protected void onPlayerUseSkill(Player player) { /* No second quest journal. */ }
				@Override protected void endCast() {
					if (!authorized.getAsBoolean() || getFirstTarget() != body) { cancelCast(); return; }
					super.endCast();
				}
				@Override protected void applyEffect(List<Effect> effects) {
					if (authorized.getAsBoolean() && effects.stream().allMatch(e -> e.getEffected() == body)) super.applyEffect(effects);
				}
			};
			skill.setItemObjectId(itemId);
			if (!skill.canUseSkill(CastState.CAST_START) || skill.getFirstTarget() != body) return false;
			return skill.useSkill();
		} finally { body.setTarget(previousTarget); }
	}
}
