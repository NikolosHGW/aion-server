package com.aionemu.gameserver.services.ai.combat;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aionemu.gameserver.controllers.observer.ActionObserver;
import com.aionemu.gameserver.controllers.observer.ObserverType;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.ai.SyntheticPlayerRole;

public final class OwnerAttackSignalSource implements CombatSignalSource {

	private static final Logger log = LoggerFactory.getLogger(OwnerAttackSignalSource.class);

	private final Player owner;
	private final Player companion;
	private final long sessionId;
	private final LongSupplier currentTimeMillis;
	private final AtomicLong sequence = new AtomicLong();
	private final AtomicReference<OwnerAttackSignal> pending = new AtomicReference<>();
	private final AtomicBoolean enabled = new AtomicBoolean();
	private final AtomicBoolean observerAttached = new AtomicBoolean();
	private final ActionObserver observer = new ActionObserver(ObserverType.ATTACK) {

		@Override
		public void attack(Creature target, int skillId) {
			if (!enabled.get() || target == null)
				return;
			long eventId = sequence.incrementAndGet();
			int targetTemplateId = target instanceof Npc npc ? npc.getNpcId() : 0;
			OwnerAttackSignal signal = new OwnerAttackSignal(eventId, sessionId, owner.getObjectId(), companion.getObjectId(), target.getObjectId(),
				targetTemplateId, owner.getWorldId(), owner.getInstanceId(), skillId, currentTimeMillis.getAsLong(), SyntheticPlayerRole.COMPANION, owner,
				companion, target);
			pending.set(signal); // bounded latest-event handoff; no combat queue or scheduler
			log.info(
				"AI_COMPANION_COMBAT action=OWNER_ATTACK_EVENT result=ACCEPTED ownerObjectId={} companionObjectId={} targetObjectId={} targetTemplateId={} eventId={} skillId={} mapId={} instanceId={} persistence=false",
				owner.getObjectId(), companion.getObjectId(), target.getObjectId(), targetTemplateId, eventId, skillId, owner.getWorldId(),
				owner.getInstanceId());
		}
	};

	public OwnerAttackSignalSource(Player owner, Player companion, long sessionId, LongSupplier currentTimeMillis) {
		this.owner = owner;
		this.companion = companion;
		this.sessionId = sessionId;
		this.currentTimeMillis = currentTimeMillis;
	}

	@Override
	public boolean enable() {
		if (!enabled.compareAndSet(false, true))
			return false;
		try {
			owner.getObserveController().addObserver(observer);
			observerAttached.set(true);
			return true;
		} catch (RuntimeException | Error e) {
			enabled.set(false);
			throw e;
		}
	}

	@Override
	public boolean disable() {
		boolean changed = enabled.getAndSet(false);
		clear();
		if (observerAttached.get()) {
			owner.getObserveController().removeObserver(observer);
			observerAttached.set(false);
		}
		return changed;
	}

	@Override
	public boolean isEnabled() {
		return enabled.get();
	}

	@Override
	public boolean isObserverAttached() {
		return observerAttached.get();
	}

	@Override
	public OwnerAttackSignal poll() {
		return pending.getAndSet(null);
	}

	@Override
	public void clear() {
		pending.set(null);
	}
}
