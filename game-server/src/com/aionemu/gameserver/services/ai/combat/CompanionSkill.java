package com.aionemu.gameserver.services.ai.combat;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.ai.action.ActionSession;
import com.aionemu.gameserver.services.ai.action.ActionSession.State;
import com.aionemu.gameserver.skillengine.model.Effect;
import com.aionemu.gameserver.skillengine.model.Skill;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;

/** Native cast/effect execution with the owning incarnation's lock around asynchronous boundaries. */
final class CompanionSkill extends Skill {

	private final Consumer<Runnable> serialize;
	private final BooleanSupplier authorized;
	private final ActionSession actions;
	private final ActionSession.Id actionId;
	private final Object reservationOwner = new Object();

	CompanionSkill(SkillTemplate template, Player caster, Creature target, Consumer<Runnable> serialize,
			BooleanSupplier authorized, ActionSession actions, ActionSession.Id actionId) {
		super(template, caster, target);
		this.serialize = serialize;
		this.authorized = authorized;
		this.actions = actions;
		this.actionId = actionId;
	}

	boolean live() {
		var receipt = actions.current();
		return receipt != null && receipt.id().equals(actionId) && !receipt.state().terminal()
			&& System.currentTimeMillis() < receipt.deadline() && authorized.getAsBoolean();
	}

	@Override
	protected void endCast() {
		serialize.accept(() -> {
			try {
				if (!live() || getEffector().getCastingSkill() != this) { cancelOwnedCast(); return; }
				super.endCast();
			}
			catch (RuntimeException failure) { fail(failure); throw failure; }
		});
	}

	@Override
	protected void applyEffect(List<Effect> effects) {
		serialize.accept(() -> {
			try {
				if (!live() || effects.isEmpty() || effects.stream().anyMatch(e -> e.getEffected() != getFirstTarget())) {
					cancelOwnedCast();
					return;
				}
				super.applyEffect(effects);
				// Success means the native effect attempt completed; dodge/resist/overheal remain native outcomes.
				actions.finish(actionId, State.SUCCEEDED, "native-effect-resolved");
			} catch (RuntimeException failure) { fail(failure); throw failure; }
			finally { releaseReservation(); }
		});
	}

	@Override protected Object getEffectReservationOwner() { return reservationOwner; }
	boolean ownsReservation(Creature target) { return target.getLifeStats().hasKillingBlowReservation(reservationOwner); }
	private void releaseReservation() {
		if (getFirstTarget() != null) getFirstTarget().getLifeStats().releaseKillingBlowReservation(reservationOwner);
	}

	private void fail(RuntimeException failure) {
		actions.finish(actionId, State.FAILED, failure.getClass().getSimpleName());
		cancelOwnedCast();
	}

	void cancelOwnedCast() {
		if (getEffector().getCastingSkill() == this) getEffector().getController().cancelCurrentSkill(null);
		else cancelCast(); // the cast may have ended but its delayed hit must still be revoked
	}

	@Override
	public void cancelCast() {
		super.cancelCast();
		releaseReservation();
		actions.finish(actionId, State.CANCELLED, "native-cast-cancelled");
	}

	@Override
	protected void updateHitTime(boolean checkAnimation) {
		// No client packet: always calculate real native motion/projectile time, even if client auditing is disabled.
		super.updateHitTime(true);
	}

	@Override
	protected boolean isSuspiciousClientHitTime(int client, int server, int tolerance, Player player) { return false; }

	@Override
	protected void onPlayerUseSkill(Player player) {
		// A companion cast is not a human quest action. Kill contribution still uses the existing owner resolver.
		// Never create a second quest journal or impersonate the owner's onUseSkill event.
	}
}
