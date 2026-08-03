package com.aionemu.gameserver.services.ai.combat;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.services.ai.SyntheticPlayerRole;

public final class CompanionCombatController {

	private static final Logger log = LoggerFactory.getLogger(CompanionCombatController.class);

	private final int ownerObjectId;
	private final int companionObjectId;
	private final long sessionId;
	private final CombatSignalSource signalSource;
	private final CombatActionGateway gateway;
	private final BooleanSupplier featureEnabled;
	private final LongSupplier currentTimeMillis;
	private final long eventTtlMs;
	private long lastConsumedEventId;
	private long lastEventAt;
	private CombatResult lastResult = new CombatResult(CombatStatus.IDLE, "none", 0, 0, 0);
	private boolean removing;

	public CompanionCombatController(int ownerObjectId, int companionObjectId, long sessionId, CombatSignalSource signalSource,
			CombatActionGateway gateway, BooleanSupplier featureEnabled, LongSupplier currentTimeMillis, long eventTtlMs) {
		this.ownerObjectId = ownerObjectId;
		this.companionObjectId = companionObjectId;
		this.sessionId = sessionId;
		this.signalSource = signalSource;
		this.gateway = gateway;
		this.featureEnabled = featureEnabled;
		this.currentTimeMillis = currentTimeMillis;
		this.eventTtlMs = eventTtlMs;
	}

	public synchronized boolean enableAssist() {
		if (removing)
			return false;
		if (!featureEnabled.getAsBoolean()) {
			lastResult = new CombatResult(CombatStatus.RUNTIME_DISABLED, "feature-disabled", 0, 0, 0);
			return false;
		}
		boolean changed = signalSource.enable();
		log.info("AI_COMPANION_COMBAT action=ASSIST result={} ownerObjectId={} companionObjectId={} sessionId={} persistence=false",
			changed ? "ENABLED" : "ALREADY_ENABLED", ownerObjectId, companionObjectId, sessionId);
		return changed;
	}

	public synchronized boolean disableAssist(String reason) {
		boolean changed = signalSource.disable();
		gateway.stop(reason);
		lastResult = new CombatResult(CombatStatus.ASSIST_DISABLED, reason, lastResult.eventId(), lastResult.targetObjectId(),
			lastResult.targetTemplateId());
		log.info(
			"AI_COMPANION_COMBAT action=ASSIST result={} reason={} ownerObjectId={} companionObjectId={} sessionId={} persistence=false",
			changed ? "DISABLED" : "ALREADY_DISABLED", reason, ownerObjectId, companionObjectId, sessionId);
		return changed;
	}

	public synchronized void tick() {
		if (removing)
			return;
		if (!featureEnabled.getAsBoolean()) {
			if (signalSource.isEnabled() || signalSource.isObserverAttached())
				disableAssist("runtime-disabled");
			return;
		}
		if (!signalSource.isEnabled())
			return;
		OwnerAttackSignal signal = signalSource.poll();
		if (signal == null)
			return;
		lastEventAt = signal.occurredAt();
		if (signal.sessionId() != sessionId || signal.ownerObjectId() != ownerObjectId || signal.companionObjectId() != companionObjectId) {
			lastResult = CombatResult.of(CombatStatus.SESSION_MISMATCH, "signal-session-identity", signal);
			logResult(lastResult);
			return;
		}
		if (signal.role() != SyntheticPlayerRole.COMPANION) {
			lastResult = CombatResult.of(CombatStatus.ROLE_MISMATCH, "signal-role", signal);
			logResult(lastResult);
			return;
		}
		if (signal.eventId() <= lastConsumedEventId) {
			lastResult = CombatResult.of(CombatStatus.DUPLICATE_EVENT, "event-sequence-already-consumed", signal);
			logResult(lastResult);
			return;
		}
		lastConsumedEventId = signal.eventId();
		long now = currentTimeMillis.getAsLong();
		if (signal.occurredAt() > now || now - signal.occurredAt() > eventTtlMs) {
			lastResult = CombatResult.of(CombatStatus.EVENT_EXPIRED, "owner-event-ttl", signal);
			logResult(lastResult);
			return;
		}
		lastResult = gateway.basicHitOnce(signal);
	}

	public synchronized void beginRemoval(String reason) {
		if (removing)
			return;
		removing = true;
		disableAssist(reason);
	}

	public synchronized boolean isAssistEnabled() {
		return signalSource.isEnabled();
	}

	public synchronized boolean isObserverAttached() {
		return signalSource.isObserverAttached();
	}

	public synchronized String status() {
		long eventAge = lastEventAt == 0 ? -1 : Math.max(0, currentTimeMillis.getAsLong() - lastEventAt);
		return "assistEnabled=" + signalSource.isEnabled()
			+ ", attackObserverAttached=" + signalSource.isObserverAttached()
			+ ", combatRemoving=" + removing
			+ ", combatSessionId=" + sessionId
			+ ", lastCombatEventId=" + lastConsumedEventId
			+ ", lastCombatEventAgeMs=" + eventAge
			+ ", lastCombatResult=" + lastResult.status()
			+ ", lastCombatReason=" + lastResult.reason()
			+ ", lastCombatTargetObjectId=" + lastResult.targetObjectId()
			+ ", lastCombatTargetTemplateId=" + lastResult.targetTemplateId();
	}

	private void logResult(CombatResult result) {
		log.info(
			"AI_COMPANION_COMBAT action=OWNER_ATTACK_EVENT result={} reason={} ownerObjectId={} companionObjectId={} targetObjectId={} targetTemplateId={} eventId={} persistence=false",
			result.status(), result.reason(), ownerObjectId, companionObjectId, result.targetObjectId(), result.targetTemplateId(), result.eventId());
	}
}
