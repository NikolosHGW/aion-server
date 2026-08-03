package com.aionemu.gameserver.services.ai.combat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.ai.SyntheticPlayerRole;

public final class CombatContributionOwnerResolver {

	public enum Context {
		FINAL_REWARD,
		TRANSFER_ON_REMOVE
	}

	private static final Logger log = LoggerFactory.getLogger(CombatContributionOwnerResolver.class);

	private final PersonalCompanionAttributionRegistry<Creature> registry = new PersonalCompanionAttributionRegistry<>();

	private CombatContributionOwnerResolver() {
	}

	public static CombatContributionOwnerResolver getInstance() {
		return SingletonHolder.INSTANCE;
	}

	public void register(Player companion, Player owner, long sessionId) {
		registry.register(companion, owner, sessionId, SyntheticPlayerRole.COMPANION);
	}

	public boolean beginRemoval(Player companion, long sessionId) {
		return registry.beginRemoval(companion, sessionId);
	}

	public void unregister(Player companion, long sessionId) {
		registry.unregister(companion, sessionId);
	}

	public boolean matches(Player companion, Player owner, long sessionId, boolean requireActive) {
		return registry.matches(companion, owner, sessionId, requireActive);
	}

	public boolean isPersonalCompanion(Creature creature) {
		return registry.contains(creature);
	}

	public Creature resolve(Creature physicalAttacker, Creature rewardTarget, Context context) {
		boolean personalCompanion = registry.contains(physicalAttacker);
		Creature contributionOwner = registry.resolveOrDefault(physicalAttacker, Creature::getMaster);
		if (personalCompanion && context == Context.FINAL_REWARD) {
			int targetTemplateId = rewardTarget instanceof Npc npc ? npc.getNpcId() : 0;
			log.info(
				"AI_COMPANION_COMBAT action={} result=MAPPED ownerObjectId={} companionObjectId={} targetObjectId={} targetTemplateId={} persistence=false",
				context, contributionOwner.getObjectId(), physicalAttacker.getObjectId(), rewardTarget == null ? 0 : rewardTarget.getObjectId(),
				targetTemplateId);
		}
		return contributionOwner;
	}

	private static final class SingletonHolder {

		private static final CombatContributionOwnerResolver INSTANCE = new CombatContributionOwnerResolver();
	}
}
