package com.aionemu.gameserver.services.ai.combat;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.ai.SyntheticPlayerRole;

public record OwnerAttackSignal(long eventId, long sessionId, int ownerObjectId, int companionObjectId, int targetObjectId,
	int targetTemplateId, int mapId, int instanceId, int skillId, long occurredAt, SyntheticPlayerRole role, Player owner, Player companion,
	Creature target) {
}
