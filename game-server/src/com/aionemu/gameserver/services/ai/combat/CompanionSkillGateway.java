package com.aionemu.gameserver.services.ai.combat;

import java.util.Comparator;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.BiPredicate;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.configs.main.GeoDataConfig;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.gameobjects.Creature;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.restrictions.PlayerRestrictions;
import com.aionemu.gameserver.services.ai.action.ActionSession;
import com.aionemu.gameserver.services.ai.PlayerActionGateway;
import com.aionemu.gameserver.services.ai.PlayerActionGateway.MovementIntent;
import com.aionemu.gameserver.services.ai.action.ActionSession.State;
import com.aionemu.gameserver.skillengine.model.SkillTemplate;
import com.aionemu.gameserver.skillengine.properties.FirstTargetRangeProperty;
import com.aionemu.gameserver.skillengine.properties.Properties.CastState;
import com.aionemu.gameserver.world.World;
import com.aionemu.gameserver.world.geo.GeoService;
import com.aionemu.gameserver.utils.PositionUtil;

/** All entry points and delayed native callbacks run inside one owner lease. No independent scheduler. */
public final class CompanionSkillGateway implements CombatActivityGateway {
	private final Player owner, body;
	private final PlayerClass role;
	private final CombatActionGateway basic;
	private final BiPredicate<OwnerAttackSignal, Boolean> damageAuthorized;
	private final BooleanSupplier enabled, incarnationAuthorized;
	private final Consumer<Runnable> serialize;
	private final PlayerActionGateway movement;
	private final BooleanSupplier approachAllowed;
	private final ActionSession actions;
	private OwnerAttackSignal targetSignal;
	private long fallbackConsumed, retryAt;
	private CompanionSkill active;
	private boolean approaching;

	public CompanionSkillGateway(Player owner, Player body, PlayerClass role, CombatActionGateway basic,
			BiPredicate<OwnerAttackSignal, Boolean> damageAuthorized, BooleanSupplier enabled, BooleanSupplier incarnationAuthorized,
			Consumer<Runnable> serialize, PlayerActionGateway movement, BooleanSupplier approachAllowed) {
		this.owner = owner; this.body = body; this.role = role; this.basic = basic;
		this.damageAuthorized = damageAuthorized; this.enabled = enabled; this.incarnationAuthorized = incarnationAuthorized;
		this.serialize = serialize; this.movement = movement; this.approachAllowed = approachAllowed;
		this.actions = new ActionSession(this::usable, System::currentTimeMillis, 32);
	}

	private boolean usable() {
		return enabled.getAsBoolean() && incarnationAuthorized.getAsBoolean() && owner != body
			&& GeoDataConfig.MODE == GeoDataConfig.Mode.ON && GeoDataConfig.CANSEE_ENABLE
			&& body.getClientConnection() == null && !body.getCommonData().isOnline()
			&& !body.isDead() && !body.getLifeStats().isAboutToDie() && !owner.isDead() && !owner.getLifeStats().isAboutToDie()
			&& present(owner) && present(body) && owner.getWorldId() == body.getWorldId() && owner.getInstanceId() == body.getInstanceId()
			&& owner.getRace() == body.getRace() && body.getPlayerClass() == (body.getLevel() < 10 ? role.getStartingClass() : role);
	}

	private static boolean present(Creature actor) {
		return actor.isSpawned() && World.getInstance().findVisibleObject(actor.getObjectId()) == actor;
	}

	@Override
	public CombatResult basicHitOnce(OwnerAttackSignal signal) {
		if (!enabled.getAsBoolean() || CompanionSkillPolicy.attacks(role).isEmpty()) {
			cancel("skills-disabled");
			return basic.basicHitOnce(signal);
		}
		if (targetSignal != null && targetSignal.target() != signal.target() && (active == null || active.getFirstTarget() != owner))
			cancel("owner-target-changed");
		targetSignal = signal;
		tick();
		return CombatResult.of(CombatStatus.IDLE, "class-activity-see-skillAction", signal);
	}

	@Override
	public void tick() {
		if (!usable()) { cancel("skill-authorization-revoked"); targetSignal = null; return; }
		var receipt = actions.current();
		if (receipt != null && !receipt.state().terminal()) {
			if (active == null || !active.live()) cancel("skill-interrupted-or-expired");
			return;
		}
		if (body.isCasting() || body.isCastingItemSkill() || System.currentTimeMillis() < body.getNextSkillUse()
			|| System.currentTimeMillis() < retryAt) return;
		retryAt = System.currentTimeMillis() + 500; // bounded rejected-cast retries, no busy loop
		if (CompanionSkillPolicy.shouldHeal(role, owner.getLifeStats().getCurrentHp(), owner.getLifeStats().getMaxHp())
			&& trySkill("CL_HEAL", owner, null)) return;
		OwnerAttackSignal signal = targetSignal;
		if (signal == null || !damageAuthorized.test(signal, false) || owner.getTarget() != signal.target()) {
			targetSignal = null; stopApproach(); return;
		}
		for (String group : CompanionSkillPolicy.attacks(role))
			if (trySkill(group, signal.target(), signal)) return;
		if (approach(signal.target())) return;
		stopApproach();
		if (signal.eventId() > fallbackConsumed) {
			fallbackConsumed = signal.eventId();
			basic.basicHitOnce(signal);
		}
	}

	private boolean trySkill(String group, Creature target, OwnerAttackSignal signal) {
		// Use the highest actually learned rank; never grant skills or invent skill levels here.
		SkillTemplate template = body.getSkillList().getAllSkills().stream()
			.map(entry -> DataManager.SKILL_DATA.getSkillTemplate(entry.getSkillId()))
			.filter(value -> CompanionSkillPolicy.supported(value, group))
			.max(Comparator.comparingInt(SkillTemplate::getLvl)).orElse(null);
		if (template == null || body.isSkillDisabled(template) || !targetAllowed(target, signal)) return false;
		var id = actions.nextId();
		CompanionSkill skill = new CompanionSkill(template, body, target, serialize,
			() -> usable() && active.getFirstTarget() == target && targetAllowed(target, signal)
				&& body.getSkillList().isSkillPresent(template.getSkillId())
				&& FirstTargetRangeProperty.set(active, template.getProperties(), CastState.CAST_END), actions, id);
		CompanionCombatPresentation.selectTarget(body, target);
		if (!PlayerRestrictions.canUseSkill(body, skill) || !skill.canUseSkill(CastState.CAST_START) || skill.getFirstTarget() != target) return false;
		stopApproach();
		if (body.getMoveController().isInMove()) movement.stopMove();
		CompanionCombatPresentation.prepareBasicAttack(body, target);
		active = skill;
		var receipt = actions.start(id, "UseSkill:" + template.getSkillId() + ":" + target.getObjectId(),
			System.currentTimeMillis() + 15_000,
			() -> new ActionSession.Outcome(skill.useSkill() ? State.RUNNING : State.REJECTED, "native-cast-request"),
			reason -> skill.cancelOwnedCast());
		return receipt.state() == State.RUNNING || receipt.state() == State.SUCCEEDED;
	}

	private boolean targetAllowed(Creature target, OwnerAttackSignal signal) {
		boolean ownsPendingHit = active != null && active.ownsReservation(target);
		if (!present(target) || target.isDead() || target.getLifeStats().isAboutToDie() && !ownsPendingHit
			|| target.getWorldId() != body.getWorldId() || target.getInstanceId() != body.getInstanceId()
			|| !body.getKnownList().knows(target) || !body.canSee(target) || !GeoService.getInstance().canSee(body, target)) return false;
		if (signal == null) return target == owner && role == PlayerClass.CLERIC && !body.isEnemy(owner);
		// Continued real owner attacks may renew the TTL for this same target, never for a different target.
		return targetSignal != null && targetSignal.target() == target && signal.target() == target
			&& owner.getTarget() == target && damageAuthorized.test(targetSignal, ownsPendingHit);
	}

	public void cancel(String reason) {
		var receipt = actions.current();
		if (receipt != null) actions.cancel(receipt.id(), reason);
		stopApproach();
	}

	private boolean approach(Creature target) {
		if (role != PlayerClass.GLADIATOR || !approachAllowed.getAsBoolean()
			|| PositionUtil.isInAttackRange(body, target, 1 + body.getGameStats().getAttackRange().getCurrent() / 1000f)) return false;
		var point = CompanionSkillPolicy.approach(body.getX(), body.getY(), body.getZ(), target.getX(), target.getY(), target.getZ(),
			owner.getX(), owner.getY(), owner.getZ());
		if (point == null) return false;
		var checked = movement.checkMovement(MovementIntent.COMBAT_APPROACH, point.x(), point.y(), point.z());
		if (!checked.success()) return false;
		if (!movement.startMove(MovementIntent.COMBAT_APPROACH, checked, Float.NaN).success()) {
			stopApproach(); return false;
		}
		approaching = true;
		return approaching;
	}

	private void stopApproach() {
		if (approaching) { approaching = false; movement.stopMove(); }
	}

	@Override public void stop(String reason) { cancel(reason); targetSignal = null; basic.stop(reason); }
	@Override public boolean holdsMovement() { return approaching || body.isCasting() || actions.current() != null && !actions.current().state().terminal(); }
	@Override public String diagnostics() { return ", skillRole=" + role + ", combatApproaching=" + approaching + ", skillAction=" + actions.current(); }
	public ActionSession.Receipt currentAction() { return actions.current(); }
}
