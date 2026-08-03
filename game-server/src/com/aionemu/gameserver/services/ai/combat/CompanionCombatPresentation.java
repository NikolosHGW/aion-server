package com.aionemu.gameserver.services.ai.combat;

import com.aionemu.gameserver.model.EmotionType;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.state.CreatureState;
import com.aionemu.gameserver.network.aion.serverpackets.SM_EMOTION;
import com.aionemu.gameserver.network.aion.serverpackets.SM_TARGET_UPDATE;
import com.aionemu.gameserver.utils.PacketSendUtility;

/**
 * Mirrors the target and weapon-mode presentation that a connected player normally publishes before a basic attack.
 */
public final class CompanionCombatPresentation {

	private CompanionCombatPresentation() {
	}

	public static void prepareBasicAttack(Player companion, Creature target) {
		if (companion.getTarget() != target) {
			companion.setTarget(target);
			PacketSendUtility.broadcastToSightedPlayers(companion, new SM_TARGET_UPDATE(companion));
		}
		if (!companion.isInState(CreatureState.WEAPON_EQUIPPED)) {
			companion.setState(CreatureState.WEAPON_EQUIPPED);
			PacketSendUtility.broadcastToSightedPlayers(companion,
				new SM_EMOTION(companion, EmotionType.ATTACKMODE_IN_STANDING, 0, target.getObjectId()));
		}
	}

	public static void stop(Player companion) {
		if (companion.getTarget() != null) {
			companion.setTarget(null);
			PacketSendUtility.broadcastToSightedPlayers(companion, new SM_TARGET_UPDATE(companion));
		}
		if (companion.isInState(CreatureState.WEAPON_EQUIPPED)) {
			companion.unsetState(CreatureState.WEAPON_EQUIPPED);
			PacketSendUtility.broadcastToSightedPlayers(companion,
				new SM_EMOTION(companion, EmotionType.NEUTRALMODE_IN_STANDING, 0, 0));
		}
	}
}
