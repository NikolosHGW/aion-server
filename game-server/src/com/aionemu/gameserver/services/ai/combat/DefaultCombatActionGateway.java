package com.aionemu.gameserver.services.ai.combat;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.model.CreatureType;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.NpcObjectType;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.state.CreatureState;
import com.aionemu.gameserver.model.templates.npc.NpcRating;
import com.aionemu.gameserver.services.ai.SyntheticPlayerRole;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.world.World;
import com.aionemu.gameserver.world.geo.GeoService;

public final class DefaultCombatActionGateway implements CombatActionGateway {

	private static final Logger log = LoggerFactory.getLogger(DefaultCombatActionGateway.class);
	private static final int FIRST_SPIKE_MAP_ID = 210010000;
	private static final float FIRST_SPIKE_SPAWN_X = 1234.05f;
	private static final float FIRST_SPIKE_SPAWN_Y = 1042.47f;
	private static final float FIRST_SPIKE_SPAWN_Z = 144.726f;
	private static final byte FIRST_SPIKE_SPAWN_HEADING = 33;
	private static final float SPAWN_COORDINATE_TOLERANCE = 0.01f;

	private final Player owner;
	private final Player companion;
	private final long sessionId;
	private final CombatContributionOwnerResolver contributionResolver;
	private final BooleanSupplier featureEnabled;
	private final BooleanSupplier assistEnabled;
	private final LongSupplier currentTimeMillis;
	private final long eventTtlMs;
	private final String allowedNpcIds;
	private final QuestCombatAuthorizationPolicy questAuthorizationPolicy;
	private final AtomicLong highestAttemptedEvent = new AtomicLong();
	private volatile long nextAttackAllowedAt;

	public DefaultCombatActionGateway(Player owner, Player companion, long sessionId,
			CombatContributionOwnerResolver contributionResolver, BooleanSupplier featureEnabled, BooleanSupplier assistEnabled,
			LongSupplier currentTimeMillis, long eventTtlMs, String allowedNpcIds, QuestCombatAuthorizationPolicy questAuthorizationPolicy) {
		this.owner = owner;
		this.companion = companion;
		this.sessionId = sessionId;
		this.contributionResolver = contributionResolver;
		this.featureEnabled = featureEnabled;
		this.assistEnabled = assistEnabled;
		this.currentTimeMillis = currentTimeMillis;
		this.eventTtlMs = eventTtlMs;
		this.allowedNpcIds = allowedNpcIds;
		this.questAuthorizationPolicy = questAuthorizationPolicy;
	}

	@Override
	public CombatResult basicHitOnce(OwnerAttackSignal signal) {
		if (signal == null)
			return new CombatResult(CombatStatus.REJECTED_BY_PLAYER_RULES, "missing-signal", 0, 0, 0);
		if (!markAttempted(signal.eventId()))
			return logResult(CombatResult.of(CombatStatus.DUPLICATE_EVENT, "event-already-attempted", signal));

		TargetAuthorization authorization = authorizeTarget(signal);
		EnumSet<CombatPreflightViolation> violations = inspect(signal, authorization);
		CombatStatus status = CombatPreflightPolicy.evaluate(violations);
		if (status != CombatStatus.HIT_STARTED)
			return logResult(CombatResult.of(status, violations.iterator().next().name().toLowerCase(), signal));

		long now = currentTimeMillis.getAsLong();
		if (now < nextAttackAllowedAt)
			return logResult(CombatResult.of(CombatStatus.COOLDOWN, "server-attack-cadence", signal));

		Npc target = (Npc) signal.target();
		if (authorization == TargetAuthorization.QUEST) {
			QuestCombatAuthorizationPolicy.Decision liveDecision = questAuthorizationPolicy.recheckLiveState(signal, target);
			questAuthorizationPolicy.log(liveDecision, signal, target);
			if (!liveDecision.allowed())
				return logResult(CombatResult.of(CombatStatus.TARGET_NOT_ALLOWED, liveDecision.reason().toLowerCase(), signal));
		}
		int attackCounterBefore = companion.getGameStats().getAttackCounter();
		companion.getController().attackTarget(target, 0, false);
		int attackCounterAfter = companion.getGameStats().getAttackCounter();
		if (attackCounterAfter == attackCounterBefore)
			return logResult(CombatResult.of(CombatStatus.REJECTED_BY_PLAYER_RULES, "checked-player-controller-rejected", signal));

		nextAttackAllowedAt = now + companion.getGameStats().getAttackSpeed().getCurrent();
		return logResult(CombatResult.of(CombatStatus.HIT_STARTED, "checked-player-basic-hit", signal));
	}

	private EnumSet<CombatPreflightViolation> inspect(OwnerAttackSignal signal, TargetAuthorization authorization) {
		EnumSet<CombatPreflightViolation> violations = EnumSet.noneOf(CombatPreflightViolation.class);
		long now = currentTimeMillis.getAsLong();
		if (!featureEnabled.getAsBoolean())
			violations.add(CombatPreflightViolation.RUNTIME_DISABLED);
		if (!assistEnabled.getAsBoolean())
			violations.add(CombatPreflightViolation.ASSIST_DISABLED);
		if (!contributionResolver.matches(companion, owner, sessionId, true))
			violations.add(CombatPreflightViolation.SESSION_MISMATCH);
		if (signal.sessionId() != sessionId || signal.owner() != owner || signal.companion() != companion
			|| signal.ownerObjectId() != owner.getObjectId() || signal.companionObjectId() != companion.getObjectId())
			violations.add(CombatPreflightViolation.SESSION_MISMATCH);
		if (signal.role() != SyntheticPlayerRole.COMPANION)
			violations.add(CombatPreflightViolation.ROLE_MISMATCH);
		if (signal.occurredAt() > now || now - signal.occurredAt() > eventTtlMs)
			violations.add(CombatPreflightViolation.EVENT_EXPIRED);
		if (companion.isDead() || companion.getLifeStats().isAboutToDie())
			violations.add(CombatPreflightViolation.COMPANION_DEAD);
		if (!companion.isSpawned() || World.getInstance().findVisibleObject(companion.getObjectId()) != companion
			|| !owner.isSpawned() || World.getInstance().findVisibleObject(owner.getObjectId()) != owner)
			violations.add(CombatPreflightViolation.REMOVING);

		Creature eventTarget = signal.target();
		if (!(eventTarget instanceof Npc target)) {
			violations.add(CombatPreflightViolation.TARGET_NOT_NPC);
			return violations;
		}
		if (authorization == TargetAuthorization.REJECTED || signal.targetObjectId() != target.getObjectId()
			|| signal.targetTemplateId() != target.getNpcId())
			violations.add(CombatPreflightViolation.TARGET_NOT_ALLOWED);
		if (target.getClass() != Npc.class || target.getNpcObjectType() != NpcObjectType.NORMAL || target.getRating() != NpcRating.NORMAL
			|| target.isBoss() || target.isRaidMonster())
			violations.add(CombatPreflightViolation.TARGET_UNSUPPORTED);
		if (!target.isSpawned() || World.getInstance().findVisibleObject(target.getObjectId()) != target)
			violations.add(CombatPreflightViolation.TARGET_UNSPAWNED);
		if (target.isDead() || target.getLifeStats().isAboutToDie())
			violations.add(CombatPreflightViolation.TARGET_DEAD);
		if (target.isInvulnerable() || target.getType(companion) == CreatureType.INVULNERABLE)
			violations.add(CombatPreflightViolation.TARGET_INVULNERABLE);
		CreatureType targetType = target.getType(companion);
		if (!owner.isEnemy(target) || !companion.isEnemy(target)
			|| targetType != CreatureType.ATTACKABLE && targetType != CreatureType.AGGRESSIVE)
			violations.add(CombatPreflightViolation.TARGET_NOT_HOSTILE);
		if (signal.mapId() != owner.getWorldId() || signal.instanceId() != owner.getInstanceId()
			|| owner.getWorldId() != companion.getWorldId() || owner.getInstanceId() != companion.getInstanceId()
			|| target.getWorldId() != companion.getWorldId() || target.getInstanceId() != companion.getInstanceId())
			violations.add(CombatPreflightViolation.MAP_OR_INSTANCE_MISMATCH);
		if (!owner.getKnownList().knows(target) || !companion.getKnownList().knows(target))
			violations.add(CombatPreflightViolation.TARGET_NOT_KNOWN);
		if (!owner.getKnownList().sees(target) || !companion.getKnownList().sees(target))
			violations.add(CombatPreflightViolation.TARGET_NOT_VISIBLE);
		float attackRange = 1 + companion.getGameStats().getAttackRange().getCurrent() / 1000f;
		if (!PositionUtil.isInAttackRange(companion, target, attackRange))
			violations.add(CombatPreflightViolation.OUT_OF_RANGE);
		if (!GeoService.getInstance().canSee(owner, target) || !GeoService.getInstance().canSee(companion, target))
			violations.add(CombatPreflightViolation.NO_LINE_OF_SIGHT);
		if (hasUnsafeEquipment(companion))
			violations.add(CombatPreflightViolation.UNSAFE_EQUIPMENT);
		if (now < nextAttackAllowedAt)
			violations.add(CombatPreflightViolation.COOLDOWN);
		return violations;
	}

	private TargetAuthorization authorizeTarget(OwnerAttackSignal signal) {
		if (!(signal.target() instanceof Npc target))
			return TargetAuthorization.REJECTED;
		Set<Integer> allowlist;
		try {
			allowlist = CombatNpcAllowlist.parse(allowedNpcIds);
		} catch (IllegalArgumentException e) {
			allowlist = Set.of();
		}
		if (target.getNpcId() == CombatNpcAllowlist.FIRST_SPIKE_NPC_ID
			&& allowlist.equals(Set.of(CombatNpcAllowlist.FIRST_SPIKE_NPC_ID)) && target.getWorldId() == FIRST_SPIKE_MAP_ID
			&& isApprovedFirstSpikeSpawn(target))
			return TargetAuthorization.LEGACY;
		QuestCombatAuthorizationPolicy.Decision decision = questAuthorizationPolicy.authorize(signal, target);
		if (QuestCombatAuthorizationPolicy.isQuestTargetTemplate(target.getNpcId()) && !decision.allowed())
			questAuthorizationPolicy.log(decision, signal, target);
		return decision.allowed() ? TargetAuthorization.QUEST : TargetAuthorization.REJECTED;
	}

	private boolean isApprovedFirstSpikeSpawn(Npc target) {
		if (target.getSpawn() == null || target.getSpawn().getWorldId() != FIRST_SPIKE_MAP_ID || target.getSpawn().isTemporarySpawn()
			|| target.getSpawn().isEventSpawn() || target.getSpawn().getWalkerId() != null || target.getSpawn().getRandomWalkRange() != 0
			|| target.getSpawn().getHeading() != FIRST_SPIKE_SPAWN_HEADING)
			return false;
		return Math.abs(target.getSpawn().getX() - FIRST_SPIKE_SPAWN_X) <= SPAWN_COORDINATE_TOLERANCE
			&& Math.abs(target.getSpawn().getY() - FIRST_SPIKE_SPAWN_Y) <= SPAWN_COORDINATE_TOLERANCE
			&& Math.abs(target.getSpawn().getZ() - FIRST_SPIKE_SPAWN_Z) <= SPAWN_COORDINATE_TOLERANCE;
	}

	private boolean hasUnsafeEquipment(Player player) {
		if (player.getEquipment().isPowerShardEquipped() || player.isInState(CreatureState.POWERSHARD))
			return true;
		Item mainHand = player.getEquipment().getMainHandWeapon();
		Item offHand = player.getEquipment().getOffHandWeapon();
		return mainHand != null && mainHand.hasGodStone() || offHand != null && offHand != mainHand && offHand.hasGodStone();
	}

	private boolean markAttempted(long eventId) {
		long current;
		do {
			current = highestAttemptedEvent.get();
			if (eventId <= current)
				return false;
		} while (!highestAttemptedEvent.compareAndSet(current, eventId));
		return true;
	}

	private enum TargetAuthorization {
		LEGACY,
		QUEST,
		REJECTED
	}

	private CombatResult logResult(CombatResult result) {
		log.info(
			"AI_COMPANION_COMBAT action=BASIC_HIT result={} reason={} ownerObjectId={} companionObjectId={} targetObjectId={} targetTemplateId={} eventId={} mapId={} instanceId={} persistence=false",
			result.status(), result.reason(), owner.getObjectId(), companion.getObjectId(), result.targetObjectId(), result.targetTemplateId(),
			result.eventId(), companion.getWorldId(), companion.getInstanceId());
		return result;
	}

	@Override
	public void stop(String reason) {
		companion.getController().cancelCurrentSkill(null);
		CompanionCombatPresentation.stop(companion);
	}
}
