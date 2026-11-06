package com.aionemu.gameserver.services.ai.progression;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.gameobjects.Persistable.PersistentState;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.network.aion.serverpackets.SM_PLAYER_INFO;
import com.aionemu.gameserver.services.SkillLearnService;
import com.aionemu.gameserver.services.ai.creation.CompanionCreationClaim;
import com.aionemu.gameserver.skillengine.model.StigmaType;
import com.aionemu.gameserver.utils.PacketSendUtility;

/** Called only inside the owner's lease. Has no source character reference, SQL, scheduler or reward path. */
public final class CompanionProgression {

	private final Player owner;
	private final Player body;
	private final CompanionCreationClaim claim;
	private final BooleanSupplier authorized;
	private boolean initialized;

	public CompanionProgression(Player owner, Player body, CompanionCreationClaim claim, BooleanSupplier authorized) {
		this.owner = owner;
		this.body = body;
		this.claim = claim;
		this.authorized = authorized;
	}

	public boolean needsSynchronization() {
		return !initialized || owner.getLevel() != body.getLevel() || body.getPlayerClass() != claim.blueprint().classAt(owner.getLevel());
	}

	public boolean ready() { return !body.isCasting() && !body.isCastingItemSkill() && !body.getLifeStats().isDead(); }

	public void synchronize() {
		requireAuthorized();
		if (!ready()) throw new IllegalStateException("Profile synchronization requires a living actor without an active cast");
		int targetLevel = owner.getLevel();
		PlayerClass targetClass = claim.blueprint().classAt(targetLevel);
		Map<Integer, Integer> desired = ordinarySkills(targetClass, targetLevel, body.getRace());
		// Resolve every referenced native template before changing anything.
		for (var skill : body.getSkillList().getAllSkills()) {
			if (DataManager.SKILL_DATA.getSkillTemplate(skill.getSkillId()) == null)
				throw new IllegalStateException("Unknown existing companion skill");
			if (skill.isStigmaSkill()) throw new IllegalStateException("Stigma progression is outside the C3 profile");
		}
		body.getCommonData().synchronizeCompanionProfile(body, targetLevel, targetClass);
		body.getGameStats().updateStatsTemplate();
		for (var skill : body.getSkillList().getAllSkills()) {
			if (!skill.isNormalSkill() || skill.getPersistentState() == PersistentState.NOACTION) continue;
			Integer level = desired.get(skill.getSkillId());
			if (level == null || level != skill.getSkillLevel()) SkillLearnService.removeSkill(body, skill.getSkillId());
		}
		for (var skill : desired.entrySet()) body.getSkillList().addSkill(body, skill.getKey(), skill.getValue());
		// Native ratio-preserving stat updates; no upgradePlayer/full heal or cooldown reset.
		body.getGameStats().updateStatsAndSpeedVisually();
		if (body.isSpawned()) PacketSendUtility.broadcastPacket(body, new SM_PLAYER_INFO(body));
		initialized = true;
	}

	private void requireAuthorized() {
		if (!authorized.getAsBoolean() || claim.blueprint() == null || owner == body || owner.getObjectId() != claim.ownerPlayerId()
			|| claim.companionPlayerId() == null || body.getObjectId() != claim.companionPlayerId()
			|| body.getAccount().getId() != claim.hostAccountId() || !body.getAccountName().equals(claim.hostAccountName())
			|| !body.getCommonData().getName().equals(claim.databaseName()) || body.getClientConnection() != null || body.getCommonData().isOnline()
			|| !body.getRace().name().equals(claim.blueprint().race()) || !body.getRace().equals(owner.getRace())
			|| !body.getGender().name().equals(claim.blueprint().gender()))
			throw new IllegalStateException("Companion progression ownership or feature gate failed");
	}

	public static Map<Integer, Integer> ordinarySkills(PlayerClass playerClass, int level, Race race) {
		if (level < 1 || level >= DataManager.PLAYER_EXPERIENCE_TABLE.getMaxLevel() || playerClass.isStartingClass() != (level < 10))
			throw new IllegalArgumentException("Unsupported companion class/level");
		Map<Integer, Integer> desired = new LinkedHashMap<>();
		// Keep native SkillLearnService's descending-level order for passive skill application.
		for (int current = level; current >= 1; current--) {
			// Native advanced-class autolearn includes level-9 proficiencies; do not replace it with only the starting family.
			List<PlayerClass> nativeClasses = current < 10 && !playerClass.isStartingClass()
				? List.of(playerClass.getStartingClass(), playerClass) : List.of(playerClass);
			for (PlayerClass nativeClass : nativeClasses) {
				for (var learn : DataManager.SKILL_TREE_DATA.getTemplatesFor(nativeClass, current, race)) {
					if (!learn.isAutolearn() || learn.isStigma() || learn.getSkillId() >= 30000) continue;
					var template = DataManager.SKILL_DATA.getSkillTemplate(learn.getSkillId());
					if (template == null) throw new IllegalStateException("Missing native skill template " + learn.getSkillId());
					if (template.getStigmaType() != StigmaType.NONE || template.isDeityAvatar()) continue;
					desired.merge(learn.getSkillId(), learn.getSkillLevel(), Math::max);
				}
			}
		}
		return Collections.unmodifiableMap(desired);
	}

	public String status() {
		return "profileClass=" + claim.blueprint().playerClass() + ", ownerLevel=" + owner.getLevel() + ", companionLevel=" + body.getLevel()
			+ ", progression=" + (needsSynchronization() ? "PENDING" : "SYNCHRONIZED");
	}
}
