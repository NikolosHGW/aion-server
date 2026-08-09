package com.aionemu.gameserver.services.ai.combat;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.model.CreatureType;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.NpcObjectType;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.npc.NpcRating;
import com.aionemu.gameserver.questEngine.model.QuestState;
import com.aionemu.gameserver.questEngine.model.QuestStatus;
import com.aionemu.gameserver.services.ai.SyntheticPlayerRole;
import com.aionemu.gameserver.services.ai.quest.CompanionQuestExecutionRuntime;
import com.aionemu.gameserver.services.ai.quest.CompanionQuestExecutionRuntime.AuthorizationView;
import com.aionemu.gameserver.services.ai.quest.QuestExecutionAllowlist;
import com.aionemu.gameserver.services.ai.quest.QuestExecutionManifest;
import com.aionemu.gameserver.services.ai.quest.QuestExecutionRole;
import com.aionemu.gameserver.services.ai.quest.QuestExecutionState;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.world.World;
import com.aionemu.gameserver.world.geo.GeoService;

public final class QuestCombatAuthorizationPolicy {

	private static final Logger log = LoggerFactory.getLogger(QuestCombatAuthorizationPolicy.class);

	private final Player owner;
	private final Player companion;
	private final long companionSessionId;
	private final CompanionQuestExecutionRuntime executionRuntime;
	private final BooleanSupplier featureEnabled;
	private final BooleanSupplier assistEnabled;
	private final Supplier<String> allowedQuestIds;

	public QuestCombatAuthorizationPolicy(Player owner, Player companion, long companionSessionId,
		CompanionQuestExecutionRuntime executionRuntime, BooleanSupplier featureEnabled, BooleanSupplier assistEnabled,
		Supplier<String> allowedQuestIds) {
		this.owner = owner;
		this.companion = companion;
		this.companionSessionId = companionSessionId;
		this.executionRuntime = executionRuntime;
		this.featureEnabled = featureEnabled;
		this.assistEnabled = assistEnabled;
		this.allowedQuestIds = allowedQuestIds;
	}

	public Decision authorize(OwnerAttackSignal signal, Npc target) {
		if (!isQuestTargetTemplate(target.getNpcId()))
			return Decision.reject("TARGET_NOT_QUEST_OBJECTIVE");
		if (!featureEnabled.getAsBoolean())
			return Decision.reject("QUEST_COMBAT_FEATURE_DISABLED");
		if (!assistEnabled.getAsBoolean())
			return Decision.reject("ASSIST_DISABLED");
		if (!QuestExecutionAllowlist.isExactFirstExecutionQuest(allowedQuestIds.get()))
			return Decision.reject("INVALID_QUEST_ALLOWLIST");
		AuthorizationView view = executionRuntime.authorizationView().orElse(null);
		if (view == null)
			return Decision.reject("NO_EXECUTION_SESSION");
		if (view.session().role() != QuestExecutionRole.PERSONAL_COMPANION || signal.role() != SyntheticPlayerRole.COMPANION
			|| view.session().ownerObjectId() != owner.getObjectId() || view.session().companionObjectId() != companion.getObjectId()
			|| view.session().companionSessionId() != companionSessionId || signal.owner() != owner || signal.companion() != companion
			|| signal.ownerObjectId() != owner.getObjectId() || signal.companionObjectId() != companion.getObjectId()
			|| signal.sessionId() != companionSessionId)
			return Decision.reject("SESSION_IDENTITY_MISMATCH");
		if (view.session().questId() != QuestExecutionAllowlist.FIRST_EXECUTION_QUEST_ID
			|| view.snapshot().executionState() != QuestExecutionState.ACTIVE_OBJECTIVE)
			return Decision.reject("EXECUTION_NOT_ACTIVE_OBJECTIVE");
		Decision live = checkLiveQuest(view, target);
		if (!live.allowed())
			return live;

		QuestExecutionManifest manifest = view.session().plan().manifest();
		if (target.getClass() != Npc.class || target.getNpcObjectType() != NpcObjectType.NORMAL || target.getRating() != NpcRating.NORMAL
			|| target.isBoss() || target.isRaidMonster() || !manifest.matchesTarget(target.getNpcId(), target.getSpawn()))
			return live.rejectAfterLive("TARGET_SPAWN_NOT_APPROVED");
		if (owner.getWorldId() != manifest.mapId() || companion.getWorldId() != manifest.mapId() || target.getWorldId() != manifest.mapId()
			|| owner.getInstanceId() != companion.getInstanceId() || owner.getInstanceId() != target.getInstanceId())
			return live.rejectAfterLive("MAP_OR_INSTANCE_MISMATCH");
		if (!target.isSpawned() || World.getInstance().findVisibleObject(target.getObjectId()) != target || target.isDead()
			|| target.getLifeStats().isAboutToDie() || target.isInvulnerable())
			return live.rejectAfterLive("TARGET_NOT_LIVE");
		CreatureType targetType = target.getType(companion);
		if (!owner.isEnemy(target) || !companion.isEnemy(target)
			|| targetType != CreatureType.ATTACKABLE && targetType != CreatureType.AGGRESSIVE)
			return live.rejectAfterLive("TARGET_NOT_HOSTILE");
		if (!owner.getKnownList().knows(target) || !companion.getKnownList().knows(target) || !owner.getKnownList().sees(target)
			|| !companion.getKnownList().sees(target))
			return live.rejectAfterLive("TARGET_NOT_VISIBLE");
		float attackRange = 1 + companion.getGameStats().getAttackRange().getCurrent() / 1000f;
		if (!PositionUtil.isInAttackRange(companion, target, attackRange))
			return live.rejectAfterLive("OUT_OF_RANGE");
		if (!GeoService.getInstance().canSee(owner, target) || !GeoService.getInstance().canSee(companion, target))
			return live.rejectAfterLive("NO_LINE_OF_SIGHT");
		return live;
	}

	/** Must be called immediately before the checked player hit. */
	public Decision recheckLiveState(OwnerAttackSignal signal, Npc target) {
		if (!featureEnabled.getAsBoolean() || !assistEnabled.getAsBoolean()
			|| !QuestExecutionAllowlist.isExactFirstExecutionQuest(allowedQuestIds.get()))
			return Decision.reject("RUNTIME_DISABLED_BEFORE_HIT");
		AuthorizationView view = executionRuntime.authorizationView().orElse(null);
		if (view == null || signal.sessionId() != companionSessionId || signal.owner() != owner || signal.companion() != companion)
			return Decision.reject("SESSION_CHANGED_BEFORE_HIT");
		return checkLiveQuest(view, target);
	}

	private Decision checkLiveQuest(AuthorizationView view, Npc target) {
		QuestExecutionManifest manifest = view.session().plan().manifest();
		QuestState nativeState = owner.getQuestStateList().getQuestState(view.session().questId());
		if (nativeState == null)
			return Decision.reject("NATIVE_QUEST_ABSENT");
		if (nativeState.getStatus() != QuestStatus.START)
			return Decision.reject("NATIVE_QUEST_NOT_START", nativeState.getStatus(), nativeState.getQuestVarById(0));
		int progress = nativeState.getQuestVarById(0);
		if (progress >= manifest.requiredProgress())
			return Decision.reject("NATIVE_OBJECTIVE_COMPLETE", nativeState.getStatus(), progress);
		if (!manifest.targetIds().contains(target.getNpcId()) || !manifest.matchesTarget(target.getNpcId(), target.getSpawn()))
			return Decision.reject("TARGET_NOT_IN_IMMUTABLE_MANIFEST", nativeState.getStatus(), progress);
		return Decision.allow(nativeState.getStatus(), progress);
	}

	public void log(Decision decision, OwnerAttackSignal signal, Npc target) {
		log.info(
			"AI_COMPANION_QUEST_EXECUTION action=COMBAT_AUTHORIZATION result={} reason={} ownerObjectId={} companionObjectId={} companionSessionId={} questId={} previousState=none newState=none nativeQuestStatus={} progress={} required=3 targetObjectId={} targetTemplateId={} eventId={} persistence=false",
			decision.allowed() ? "ALLOWED" : "REJECTED", decision.reason(), owner.getObjectId(), companion.getObjectId(), companionSessionId,
			QuestExecutionAllowlist.FIRST_EXECUTION_QUEST_ID,
			decision.nativeQuestStatus() == null ? "UNKNOWN" : decision.nativeQuestStatus(), decision.progress(), target.getObjectId(), target.getNpcId(),
			signal.eventId());
	}

	public static boolean isQuestTargetTemplate(int npcId) {
		return npcId == QuestExecutionManifest.TARGET_1_ID || npcId == QuestExecutionManifest.TARGET_2_ID;
	}

	public record Decision(boolean allowed, String reason, QuestStatus nativeQuestStatus, int progress) {

		public static Decision allow(QuestStatus nativeQuestStatus, int progress) {
			return new Decision(true, "ALLOWED", nativeQuestStatus, progress);
		}

		public static Decision reject(String reason) {
			return reject(reason, null, -1);
		}

		public static Decision reject(String reason, QuestStatus nativeQuestStatus, int progress) {
			return new Decision(false, reason, nativeQuestStatus, progress);
		}

		public Decision rejectAfterLive(String nextReason) {
			return reject(nextReason, nativeQuestStatus, progress);
		}
	}
}
