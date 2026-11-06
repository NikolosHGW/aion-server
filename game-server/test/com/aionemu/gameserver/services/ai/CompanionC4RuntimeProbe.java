package com.aionemu.gameserver.services.ai;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.aionemu.gameserver.GameServer;
import com.aionemu.gameserver.dao.CompanionBodyDAO;
import com.aionemu.gameserver.dao.CompanionPersistenceDAO;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.spawns.SpawnGroup;
import com.aionemu.gameserver.model.templates.spawns.SpawnTemplate;
import com.aionemu.gameserver.services.ai.action.ActionSession.State;
import com.aionemu.gameserver.services.ai.combat.*;
import com.aionemu.gameserver.services.ai.progression.CompanionProgression;
import com.aionemu.gameserver.services.ai.quest.CompanionQuestExecutionRuntime;
import com.aionemu.gameserver.services.AccountService;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.spawnengine.SpawnEngine;
import com.aionemu.gameserver.world.World;

/** Disposable native integration probe. Fixture actors have no connection; the production lease admission is not simulated. */
public final class CompanionC4RuntimeProbe {
	private static final ReentrantLock LOCK = new ReentrantLock();
	private static final Consumer<Runnable> SERIAL = action -> { LOCK.lock(); try { action.run(); } finally { LOCK.unlock(); } };
	private static void check(boolean value, String text) {
		if (!value) throw new AssertionError(text);
		System.out.println("C4_CHECK PASS " + text);
	}
	private static void await(BooleanSupplier condition, String text) throws Exception {
		long end = System.currentTimeMillis() + 12_000;
		while (!condition.getAsBoolean() && System.currentTimeMillis() < end) Thread.sleep(50);
		check(condition.getAsBoolean(), text);
	}
	public static void main(String[] args) throws Exception {
		Properties config = new Properties();
		try (var in = Files.newInputStream(Path.of("config/mygs.properties"))) { config.load(in); }
		if (!config.getProperty("database.url", "").startsWith("jdbc:mysql://127.0.0.1:53412/ai_c4_runtime?"))
			throw new IllegalStateException("Disposable C4 database guard failed");
		if (args.length != 1 || !List.of("create", "exercise", "restore").contains(args[0])) throw new IllegalArgumentException();
		try {
			var main = GameServer.class.getDeclaredMethod("main"); main.setAccessible(true); main.invoke(null);
			run(args[0]);
			System.out.println("C4_RUNTIME_PASS phase=" + args[0]);
			GameServer.initShutdown(0, 0);
		} catch (Throwable failure) {
			failure.printStackTrace(); System.err.println("C4_RUNTIME_FAIL"); GameServer.initShutdown(1, 0);
		}
	}
	private static void spawn(Player player, float x) {
		World.getInstance().setPosition(player, 210010000, 1, x, 1042.47f, 144.726f, (byte) 33);
		World.getInstance().storeObject(player); World.getInstance().spawn(player);
		player.getLifeStats().cancelAllTasks();
	}
	private static void freezeRegen(Player... actors) {
		for (Player actor : actors) actor.getLifeStats().cancelRestoreTask();
	}
	static void run(String phase) throws Exception {
		for (String group : List.of("CL_HEAL", "CL_SMITE", "WA_ROBUSTHIT", "WA_ROBUSTBLOW")) {
			var ranks = DataManager.SKILL_DATA.getSkillTemplatesByGroup(group);
			check(!ranks.isEmpty() && ranks.stream().allMatch(t -> CompanionSkillPolicy.supported(t, group)), "native template shape audit: " + group);
		}
		if (phase.equals("create")) CompanionC3RuntimeProbe.run("create");
		int ownerId = (int) CompanionC3RuntimeProbe.scalar("SELECT id FROM players WHERE name='Cthreeownera'");
		var repository = new CompanionPersistenceDAO();
		var claim = repository.loadCreationClaim(ownerId).orElseThrow();
		Player owner = CompanionC3RuntimeProbe.load(770031, "c3_owner_a", ownerId, false);
		var account = AccountService.loadServerControlledAccount(880003, "ai_c3_host", claim.companionPlayerId());
		CompanionC3RuntimeProbe.rejected(() -> PlayerService.getServerControlledPlayer(claim.companionPlayerId(), account, PlayerClass.GLADIATOR),
			"mismatched class cannot restore companion profile");
		Player healer = PlayerService.getServerControlledPlayer(claim.companionPlayerId(), account, claim.blueprint().advancedClass());
		CompanionBodyDAO.verifyLoaded(healer);
		check(healer.getLevel() == 10, "saved exact level 10 restored before native vitals load");
		if (phase.equals("restore")) {
			check(CompanionC3RuntimeProbe.scalar("SELECT mp FROM player_life_stats WHERE player_id=" + healer.getObjectId())
				== healer.getLifeStats().getCurrentMp(), "restart loads exact checkpoint mana before progression");
		}
		new CompanionProgression(owner, healer, claim, () -> true).synchronize();
		if (phase.equals("restore")) {
			check(healer.getLifeStats().getCurrentMp() < healer.getLifeStats().getMaxMp(), "spent mana survives restart");
			check(healer.getSkillCoolDowns().containsKey(1229), "native offensive cooldown survives restart");
			check(!healer.isCasting() && healer.getClientConnection() == null, "no pending cast or fabricated connection restored");
			CompanionC3RuntimeProbe.close(owner); CompanionC3RuntimeProbe.close(healer); return;
		}
		spawn(owner, 1231); spawn(healer, 1232);
		owner.getKnownList().update(); healer.getKnownList().update();
		AtomicBoolean enabled = new AtomicBoolean(true), authorized = new AtomicBoolean(true);
		var resolver = CombatContributionOwnerResolver.getInstance(); resolver.register(healer, owner, 404);
		var basic = basic(owner, healer, 404);
		var gateway = new CompanionSkillGateway(owner, healer, PlayerClass.CLERIC, basic, basic::allowsSkillTarget,
			enabled::get, () -> authorized.get() && resolver.matches(healer, owner, 404, true), SERIAL,
			new DefaultPlayerActionGateway(healer, owner), () -> true);
		// Fixture injuries/mana are test setup only; every tested recovery below uses the actual skill engine.
		owner.getLifeStats().setCurrentHp(owner.getLifeStats().getMaxHp() / 4);
		healer.getLifeStats().setCurrentMp(healer.getLifeStats().getMaxMp());
		freezeRegen(owner, healer);
		int beforeHp = owner.getLifeStats().getCurrentHp(), beforeMp = healer.getLifeStats().getCurrentMp();
		SERIAL.accept(gateway::tick);
		check(healer.isCasting() && gateway.holdsMovement(), "healer starts native cast and reserves movement");
		await(() -> gateway.currentAction().state().terminal(), "native healing action terminates");
		check(gateway.currentAction().state() == State.SUCCEEDED && owner.getLifeStats().getCurrentHp() > beforeHp, "native heal restores owner HP");
		check(healer.getLifeStats().getCurrentMp() < beforeMp, "native heal spends mana");
		check(healer.getClientConnection() == null && !healer.getCommonData().isOnline(), "native cast needs no fake connection or online flag");

		Thread.sleep(1500); owner.getLifeStats().setCurrentHp(beforeHp); freezeRegen(owner, healer);
		SERIAL.accept(gateway::tick); check(healer.isCasting(), "second heal starts for cancellation test");
		SERIAL.accept(() -> gateway.stop("owner-dismissed"));
		Thread.sleep(3000);
		check(owner.getLifeStats().getCurrentHp() == beforeHp && gateway.currentAction().state() == State.CANCELLED,
			"dismiss cancels delayed native healing");
		int afterCancelMp = healer.getLifeStats().getCurrentMp();
		SERIAL.accept(gateway::tick); check(healer.isCasting(), "third heal starts for flag rollback");
		enabled.set(false); // no controller tick: the asynchronous cast-end gate must reject by itself
		Thread.sleep(3000);
		check(gateway.currentAction().state() == State.CANCELLED && owner.getLifeStats().getCurrentHp() == beforeHp
			&& healer.getLifeStats().getCurrentMp() >= afterCancelMp,
			"flag revocation before cast end spends nothing and heals nobody: hp=" + owner.getLifeStats().getCurrentHp()
				+ "/" + beforeHp + " mp=" + healer.getLifeStats().getCurrentMp() + "/" + afterCancelMp + " " + gateway.currentAction());
		enabled.set(true);
		healer.getLifeStats().setCurrentMp(0); SERIAL.accept(gateway::tick);
		check(!healer.isCasting(), "native insufficient mana refuses cast");
		healer.getLifeStats().setCurrentMp(healer.getLifeStats().getMaxMp());
		Thread.sleep(600); authorized.set(false); SERIAL.accept(gateway::tick);
		check(!healer.isCasting(), "stale owner incarnation refuses action"); authorized.set(true);
		World.getInstance().setPosition(owner, 210010000, 1, 1300, 1042.47f, 144.726f, (byte) 33); World.getInstance().spawn(owner);
		Thread.sleep(600); SERIAL.accept(gateway::tick);
		check(!healer.isCasting(), "native range rejects distant owner");
		World.getInstance().setPosition(owner, 210010000, 1, 1231, 1042.47f, 144.726f, (byte) 33); World.getInstance().spawn(owner);

		// A second living Player is not a heal target; only the bound owner is inspected.
		int otherId = (int) CompanionC3RuntimeProbe.scalar("SELECT id FROM players WHERE name='Cthreeownerb'");
		Player otherOwner = CompanionC3RuntimeProbe.load(770032, "c3_owner_b", otherId, false); spawn(otherOwner, 1230);
		otherOwner.getLifeStats().setCurrentHp(1); owner.getLifeStats().setCurrentHp(owner.getLifeStats().getMaxHp());
		Thread.sleep(600); SERIAL.accept(gateway::tick);
		check(!healer.isCasting() && otherOwner.getLifeStats().getCurrentHp() == 1, "another owner's injury cannot trigger healing");
		var wrongOwnerGateway = new CompanionSkillGateway(otherOwner, healer, PlayerClass.CLERIC, basic, basic::allowsSkillTarget,
			() -> true, () -> resolver.matches(healer, otherOwner, 404, true), SERIAL, new DefaultPlayerActionGateway(healer, otherOwner), () -> true);
		SERIAL.accept(wrongOwnerGateway::tick);
		check(!healer.isCasting(), "foreign owner/body attribution pair rejected");
		check(!CompanionSkillPolicy.supported(DataManager.SKILL_DATA.getSkillTemplate(624), "FI_ROBUSTCRASH"), "unaudited secondary-effect attack excluded");

		var spawn = new SpawnTemplate(new SpawnGroup(210010000, 210115, 0, null), 1234.05f, 1042.47f, 144.726f, (byte) 33, 0, null, 0);
		Npc target = (Npc) SpawnEngine.spawnObject(spawn, 1);
		owner.getKnownList().update(); healer.getKnownList().update(); target.getKnownList().update();
		owner.setTarget(target);
		var signal = signal(owner, healer, target, 404, 1);
		check(basic.allowsSkillTarget(signal), "native first-spike target authorization succeeds");
		int targetHp = target.getLifeStats().getCurrentHp();
		Thread.sleep(600);
		SERIAL.accept(() -> gateway.basicHitOnce(signal));
		check(healer.isCasting(), "cleric starts native offensive skill " + gateway.diagnostics());
		await(() -> gateway.currentAction().state().terminal(), "native offensive action terminates");
		check(gateway.currentAction().state() == State.SUCCEEDED && target.getLifeStats().getCurrentHp() < targetHp,
			"native offensive effect damages approved target: hp=" + target.getLifeStats().getCurrentHp() + "/" + targetHp + " " + gateway.diagnostics());
		check(healer.isSkillDisabled(DataManager.SKILL_DATA.getSkillTemplate(4013)), "native offensive cooldown is active");
		check(resolver.resolve(healer, target, CombatContributionOwnerResolver.Context.FINAL_REWARD) == owner,
			"damage contribution belongs only to owner");
		World.getInstance().removeObject(target);
		Thread.sleep(3000);
		Npc delayedTarget = (Npc) SpawnEngine.spawnObject(spawn, 1);
		owner.setTarget(delayedTarget); owner.getKnownList().update(); healer.getKnownList().update();
		int delayedHp = delayedTarget.getLifeStats().getCurrentHp();
		SERIAL.accept(() -> gateway.basicHitOnce(signal(owner, healer, delayedTarget, 404, 2)));
		check(healer.isCasting(), "delayed-hit revocation fixture begins cast");
		await(() -> !healer.isCasting(), "offensive cast ends before delayed impact");
		check(gateway.currentAction().state() == State.RUNNING, "receipt remains running until real impact");
		enabled.set(false);
		await(() -> gateway.currentAction().state().terminal(), "delayed effect observes rollback flag without scheduler tick");
		check(delayedTarget.getLifeStats().getCurrentHp() == delayedHp && gateway.currentAction().state() == State.CANCELLED,
			"disabled companion cannot land already scheduled native hit");
		check(!delayedTarget.getLifeStats().isAboutToDie(), "cancelled lethal hit releases its pending death marker");
		World.getInstance().removeObject(delayedTarget);
		enabled.set(true);

		var fighterClaim = repository.loadCreationClaim(otherId).orElseThrow();
		Player fighter = CompanionC3RuntimeProbe.load(880003, "ai_c3_host", fighterClaim.companionPlayerId(), true);
		otherOwner.getCommonData().synchronizeCompanionProfile(otherOwner, 3, PlayerClass.WARRIOR);
		new CompanionProgression(otherOwner, fighter, fighterClaim, () -> true).synchronize();
		otherOwner.getLifeStats().setCurrentHp(otherOwner.getLifeStats().getMaxHp());
		spawn(fighter, 1233); resolver.register(fighter, otherOwner, 405);
		var fighterBasic = basic(otherOwner, fighter, 405);
		var fighterGateway = new CompanionSkillGateway(otherOwner, fighter, PlayerClass.GLADIATOR, fighterBasic, fighterBasic::allowsSkillTarget,
			() -> true, () -> resolver.matches(fighter, otherOwner, 405, true), SERIAL, new DefaultPlayerActionGateway(fighter, otherOwner), () -> true);
		Npc fighterTarget = (Npc) SpawnEngine.spawnObject(spawn, 1);
		otherOwner.setTarget(fighterTarget); otherOwner.getKnownList().update(); fighter.getKnownList().update();
		SERIAL.accept(() -> fighterGateway.basicHitOnce(signal(otherOwner, fighter, fighterTarget, 405, 1)));
		check(fighterGateway.currentAction() != null && fighterGateway.currentAction().intent().startsWith("UseSkill:2864:"),
			"gladiator starts learned Ferocious Strike rather than unavailable chain follow-up");
		await(() -> fighterGateway.currentAction().state().terminal(), "first native melee link resolves");
		check(fighterGateway.currentAction().state() == State.SUCCEEDED, "first melee effect runs through native engine");
		Thread.sleep(600);
		SERIAL.accept(() -> fighterGateway.basicHitOnce(signal(otherOwner, fighter, fighterTarget, 405, 2)));
		check(fighterGateway.currentAction().intent().startsWith("UseSkill:2877:"), "native chain state enables learned Robust Blow");
		await(() -> fighterGateway.currentAction().state().terminal(), "second native melee link resolves");
		SERIAL.accept(() -> fighterGateway.stop("probe-complete"));
		resolver.unregister(fighter, 405); World.getInstance().removeObject(fighterTarget);
		World.getInstance().removeObject(fighter); CompanionC3RuntimeProbe.close(fighter);
		// Use a long native cooldown solely as a persistence fixture; never reset cooldowns in the gateway.
		healer.setSkillCoolDown(1229, System.currentTimeMillis() + 3_600_000);
		SERIAL.accept(() -> gateway.stop("checkpoint"));
		World.getInstance().removeObject(healer); healer.getLifeStats().cancelAllTasks();
		// Match production cleanup: checkpoint precedes removal of passive effects and stat modifiers.
		check(healer.getLifeStats().getCurrentMp() < healer.getLifeStats().getMaxMp(), "native mana expenditure remains before checkpoint");
		CompanionBodyDAO.save(CompanionBodyDAO.snapshot(ownerId, healer));
		check(CompanionC3RuntimeProbe.scalar("SELECT mp FROM player_life_stats WHERE player_id=" + healer.getObjectId())
			== healer.getLifeStats().getCurrentMp(), "checkpoint writes exact native remaining mana");
		check(CompanionC3RuntimeProbe.scalar("SELECT COUNT(*) FROM player_quests WHERE player_id=" + healer.getObjectId()) == 0,
			"native casts never create companion quest journal entries");
		for (Player player : List.of(owner, healer, otherOwner)) { World.getInstance().removeObject(player); CompanionC3RuntimeProbe.close(player); }
		resolver.unregister(healer, 404);
	}
	private static DefaultCombatActionGateway basic(Player owner, Player body, long generation) {
		return new DefaultCombatActionGateway(owner, body, generation, CombatContributionOwnerResolver.getInstance(), () -> true, () -> true,
			System::currentTimeMillis, 3000, "210115", new QuestCombatAuthorizationPolicy(owner, body, generation,
				new CompanionQuestExecutionRuntime(owner.getObjectId(), body.getObjectId(), generation), () -> false, () -> true, () -> ""));
	}
	private static OwnerAttackSignal signal(Player owner, Player body, Npc target, long generation, long sequence) {
		return new OwnerAttackSignal(sequence, generation, owner.getObjectId(), body.getObjectId(), target.getObjectId(), target.getNpcId(),
			owner.getWorldId(), owner.getInstanceId(), 0, System.currentTimeMillis(), SyntheticPlayerRole.COMPANION, owner, body, target);
	}
}
