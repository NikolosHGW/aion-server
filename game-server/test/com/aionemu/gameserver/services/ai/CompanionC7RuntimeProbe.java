package com.aionemu.gameserver.services.ai;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import com.aionemu.gameserver.GameServer;
import com.aionemu.gameserver.dao.*;
import com.aionemu.gameserver.model.gameobjects.*;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.spawns.*;
import com.aionemu.gameserver.questEngine.QuestEngine;
import com.aionemu.gameserver.questEngine.model.*;
import com.aionemu.gameserver.services.*;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.services.ai.quest.*;
import com.aionemu.gameserver.services.ai.combat.*;
import com.aionemu.gameserver.services.ai.persistence.*;
import com.aionemu.gameserver.spawnengine.SpawnEngine;
import com.aionemu.gameserver.world.World;

/** Native data/quest-hook integration, using the explicitly disposable C5 fixture. No client acceptance is implied. */
public final class CompanionC7RuntimeProbe {
	private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); System.out.println("C7_CHECK PASS " + message); }
	public static void main(String[] args) throws Exception {
		var config = new Properties();
		try (var in=Files.newInputStream(Path.of("config/mygs.properties"))) { config.load(in); }
		if (!config.getProperty("database.url", "").startsWith("jdbc:mysql://127.0.0.1:53413/ai_c5_runtime?")
			|| args.length != 1 || !Set.of("exercise","restore").contains(args[0])) throw new IllegalStateException("Disposable C7 database guard");
		try { var main=GameServer.class.getDeclaredMethod("main"); main.setAccessible(true); main.invoke(null);
			run(args[0]); System.out.println("C7_RUNTIME_PASS phase=" + args[0]); GameServer.initShutdown(0,0);
		} catch(Throwable failure) { failure.printStackTrace(); System.err.println("C7_RUNTIME_FAIL"); GameServer.initShutdown(1,0); }
	}
	private static void spawn(Player actor, float x) {
		World.getInstance().setPosition(actor,210010000,1,x,1669.97f,125.252f,(byte)79);
		World.getInstance().storeObject(actor); World.getInstance().spawn(actor); actor.getLifeStats().cancelAllTasks();
	}
	static void run(String phase) throws Exception {
		int id=(int)CompanionC3RuntimeProbe.scalar("SELECT id FROM players WHERE name='Cthreeownera'");
		var dao=new CompanionPersistenceDAO(); var claim=dao.loadCreationClaim(id).orElseThrow();
		Player owner=CompanionC3RuntimeProbe.load(770031,"c3_owner_a",id,false);
		Player body=PlayerService.getServerControlledPlayer(claim.companionPlayerId(), AccountService.loadServerControlledAccount(880003,"ai_c3_host",claim.companionPlayerId()),claim.blueprint().advancedClass());
		spawn(owner,466.5f); spawn(body,467);
		var candidate=new QuestGoalCandidateProvider().provide(owner,1120);
		check(candidate.success(),"1120 native MonsterHunt candidate: " + candidate.reason());
		var plan=new QuestExecutionPlanProvider().build(candidate.plan());
		check(plan.manifest().approvedSpawnCount()==11,"1120 exact native counter and eleven stationary spawns audited");
		check(new QuestExecutionPlanProvider().build(new QuestGoalCandidateProvider().provide(owner,1102).plan()).manifest().approvedSpawnCount()==39,"1102 original manifest still valid");
		if (phase.equals("restore")) {
			var saved=dao.load(id).orElseThrow();
			check(CompanionGoalRestorePolicy.validate(saved,id,body.getObjectId(),candidate.plan())==CompanionGoalRestorePolicy.RestoreResult.RESTORED,"1120 chosen intent survives separate JVM with same semantics");
			check(CompanionActivityGuide.next(candidate.plan(),QuestNativeSnapshot.readOnly(owner.getQuestStateList().getQuestState(1120))).contains("4/9"),"restart status reads native four-of-nine progress");
		} else {
			check(new ReadOnlyQuestEligibility().check(owner,1120).eligible(),"native owner eligible at level 10");
			check(QuestService.startQuest(new QuestEnv(null,owner,1120,0)),"native QuestService accepts owner quest");
			var time=new AtomicLong(1000); var allowed=new AtomicReference<>("1102,1120"); var enabled=new AtomicBoolean(true);
			var runtime=new CompanionQuestExecutionRuntime(id,body.getObjectId(),707);
			runtime.attach(plan,()->QuestNativeSnapshot.readOnly(owner.getQuestStateList().getQuestState(1120)),
				()->new QuestExecutionRuntimeContext(id,body.getObjectId(),707,QuestExecutionRole.PERSONAL_COMPANION,210010000,1,210010000,1,true,true,true),
				transition->{},time::get,500);
			var fp=plan.manifest().approvedTargetSpawns().getFirst();
			Npc npc=(Npc)SpawnEngine.spawnObject(new SpawnTemplate(new SpawnGroup(fp.mapId(),fp.npcId(),0,null),fp.x(),fp.y(),fp.z(),fp.heading(),0,null,0),1);
			owner.getKnownList().update(); body.getKnownList().update(); npc.getKnownList().update();
			var signal=new OwnerAttackSignal(1,707,id,body.getObjectId(),npc.getObjectId(),npc.getNpcId(),210010000,1,0,System.currentTimeMillis(),SyntheticPlayerRole.COMPANION,owner,body,npc);
			var policy=new QuestCombatAuthorizationPolicy(owner,body,707,runtime,enabled::get,()->true,allowed::get);
			var decision=policy.authorize(signal,npc);
			check(decision.allowed(),"1120 checked quest target authorization: " + decision.reason());
			allowed.set("1102"); check(!policy.recheckLiveState(signal,npc).allowed(),"removing selected quest revokes pending hit immediately"); allowed.set("1102,1120");
			enabled.set(false); check(!policy.authorize(signal,npc).allowed(),"quest combat rollback flag rejects target"); enabled.set(true);
			// Inject events at the native quest-engine boundary, not an actual player attack/kill.
			for(int i=0;i<4;i++) check(QuestEngine.getInstance().onKill(new QuestEnv(npc,owner,1120,0)),"native quest kill hook " + (i+1));
			time.addAndGet(500); runtime.pollIfDue();
			check(runtime.authorizationView().orElseThrow().snapshot().currentProgress()==4,"tracker reads native 4/9 counter");
			check(body.getQuestStateList().getQuestState(1120)==null,"companion has no second quest journal");
			int otherId=(int)CompanionC3RuntimeProbe.scalar("SELECT id FROM players WHERE name='Cthreeownerb'");
			Player other=CompanionC3RuntimeProbe.load(770032,"c3_owner_b",otherId,false);
			check(other.getQuestStateList().getQuestState(1120)==null,"second owner receives no quest progress"); CompanionC3RuntimeProbe.close(other);
			PlayerQuestListDAO.store(owner);
			var binding=dao.load(id).orElseThrow().binding();
			dao.saveGoalForExistingBinding(binding,new PersistedGoalIntent(id,CompanionGoalRestorePolicy.GOAL_TYPE_COMPLETE_QUEST,1120,QuestGoalSemanticFingerprint.PLAN_VERSION,QuestGoalSemanticFingerprint.sha256(candidate.plan())));
			runtime.clear("rollback"); check(!policy.authorize(signal,npc).allowed(),"cleared activity cannot authorize old signal");
			World.getInstance().removeObject(npc);
		}
		for(Player actor:List.of(owner,body)) { World.getInstance().removeObject(actor); CompanionC3RuntimeProbe.close(actor); }
	}
}
