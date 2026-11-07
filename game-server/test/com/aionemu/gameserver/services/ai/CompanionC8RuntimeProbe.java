package com.aionemu.gameserver.services.ai;

import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import com.aionemu.gameserver.GameServer;
import com.aionemu.gameserver.configs.main.AIConfig;
import com.aionemu.gameserver.dao.*;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.player.*;
import com.aionemu.gameserver.services.*;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.services.ai.action.TrackedMovementGateway;
import com.aionemu.gameserver.services.ai.combat.*;
import com.aionemu.gameserver.services.ai.creation.*;
import com.aionemu.gameserver.services.ai.persistence.*;
import com.aionemu.gameserver.services.ai.progression.CompanionProgression;
import com.aionemu.gameserver.services.ai.quest.*;
import com.aionemu.gameserver.taskmanager.tasks.PlayerMoveTaskManager;
import com.aionemu.gameserver.world.World;

/** C8 acceptance harness. Only the fixed disposable endpoint is permitted.
 * Native Player/DB/world/movement/cleanup are real. Owner connectivity is an explicit
 * test seam; this does not test login packets or replace two-client acceptance. */
public final class CompanionC8RuntimeProbe {
	private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); System.out.println("C8_CHECK PASS " + message); }
	private static boolean causedBy(Throwable error, String message) {
		if (error.getMessage()!=null && error.getMessage().contains(message)) return true;
		if (error.getCause()!=null && causedBy(error.getCause(),message)) return true;
		return Arrays.stream(error.getSuppressed()).anyMatch(suppressed->causedBy(suppressed,message));
	}
	public static void main(String[] args) throws Exception {
		var config=new Properties();
		try(var in=Files.newInputStream(Path.of("config/mygs.properties"))) { config.load(in); }
		if (!config.getProperty("database.url", "").startsWith("jdbc:mysql://127.0.0.1:53416/ai_c8_runtime?") || args.length!=1)
			throw new IllegalStateException("C8 disposable database guard");
		try {
			var main=GameServer.class.getDeclaredMethod("main"); main.setAccessible(true); main.invoke(null);
			switch(args[0]) {
				case "fresh-items" -> CompanionC5RuntimeProbe.run("create");
				case "items-restart" -> CompanionC5RuntimeProbe.run("restore");
				case "combat" -> CompanionC4RuntimeProbe.run("exercise");
				case "combat-restart" -> CompanionC4RuntimeProbe.run("restore");
				case "navigation" -> CompanionC6RuntimeProbe.run();
				case "quest" -> CompanionC7RuntimeProbe.run("exercise");
				case "quest-restart" -> CompanionC7RuntimeProbe.run("restore");
				case "load-cleanup" -> load();
				case "load-restart" -> restoreLoad();
				default -> throw new IllegalArgumentException("Unknown C8 phase");
			}
			System.out.println("C8_RUNTIME_PASS phase="+args[0]); GameServer.initShutdown(0,0);
		} catch(Throwable failure) { failure.printStackTrace(); System.err.println("C8_RUNTIME_FAIL phase="+args[0]); GameServer.initShutdown(1,0); }
	}
	@SuppressWarnings("unchecked")
	private static CompanionSessions<Player,Object> sessions(CompanionService service) throws Exception {
		var field=CompanionService.class.getDeclaredField("sessions"); field.setAccessible(true);
		return (CompanionSessions<Player,Object>)field.get(service);
	}
	private static void spawn(Player actor,float x) {
		World.getInstance().setPosition(actor,210010000,1,x,1042.47f,144.726f,(byte)33);
		World.getInstance().storeObject(actor); World.getInstance().spawn(actor); actor.getLifeStats().cancelAllTasks();
	}
	private record Actor(Player owner, Player body, ServerControlledPlayer wrapper, AtomicInteger ticks, AtomicBoolean connected) { }
	private static Actor actor(int index, CompanionService service, SyntheticPlayerScheduler scheduler) throws Exception {
		var creator=new DefaultCompanionPlayerCreationGateway(); var dao=new CompanionPersistenceDAO();
		String suffix=String.valueOf((char)('a'+index)), name="Cloadowner"+suffix, accountName="c8_owner_"+suffix;
		int account=790100+index;
		var ownerRecord=creator.create(new CompanionCreationRequest(account,"ELYOS","FEMALE"),name,account,accountName);
		boolean healer=index%2==1;
		CompanionC3RuntimeProbe.fixture("UPDATE players SET player_class='"+(healer?"PRIEST":"WARRIOR")+"' WHERE id="+ownerRecord.playerId());
		var blueprint=new CompanionSourceDAO().snapshot(account,name,"Load"+suffix,healer?"cleric":"gladiator","ELYOS");
		var created=new CompanionCreationService(dao,creator).create(new CompanionCreationRequest(ownerRecord.playerId(),"ELYOS","FEMALE",blueprint),880008,"ai_c8_host");
		CompanionC3RuntimeProbe.fixture("UPDATE players SET player_class='"+blueprint.advancedClass()+"', exp="
			+(DataManager.PLAYER_EXPERIENCE_TABLE.getStartExpForLevel(10)+1)+" WHERE id="+ownerRecord.playerId());
		Player owner=CompanionC3RuntimeProbe.load(account,accountName,ownerRecord.playerId(),false);
		Player body=PlayerService.getServerControlledPlayer(created.body().playerId(),AccountService.loadServerControlledAccount(880008,"ai_c8_host",created.body().playerId()),blueprint.advancedClass());
		var claim=dao.loadCreationClaim(owner.getObjectId()).orElseThrow();
		new CompanionProgression(owner,body,claim,()->true).synchronize();
		body.setServerControlledDisplayName(blueprint.displayName());
		spawn(owner,1237); spawn(body,1230);
		var sessions=sessions(service); var lease=sessions.reserve(owner.getObjectId(),owner,10); sessions.claimBody(lease,body.getObjectId());
		var movement=new TrackedMovementGateway(new DefaultPlayerActionGateway(body,owner,true),()->sessions.usable(lease),System::currentTimeMillis,
			body::getX,body::getY,body::getZ,()->body.getMoveController().isInMove());
		var connected=new AtomicBoolean(true); var nativeContext=new PlayerCompanionContext(owner,body);
		CompanionContext context=(CompanionContext)Proxy.newProxyInstance(CompanionContext.class.getClassLoader(),new Class<?>[]{CompanionContext.class},
			(proxy,method,arguments)->method.getName().equals("ownerConnected")?connected.get():method.invoke(nativeContext,arguments));
		var execution=new CompanionQuestExecutionRuntime(owner.getObjectId(),body.getObjectId(),lease.generation());
		var signalSource=new OwnerAttackSignalSource(owner,body,lease.generation(),System::currentTimeMillis);
		var basic=new DefaultCombatActionGateway(owner,body,lease.generation(),CombatContributionOwnerResolver.getInstance(),()->true,signalSource::isEnabled,
			System::currentTimeMillis,3000,"210115",new QuestCombatAuthorizationPolicy(owner,body,lease.generation(),execution,()->false,signalSource::isEnabled,()->"1102,1120"));
		var combat=new CompanionCombatController(owner.getObjectId(),body.getObjectId(),lease.generation(),signalSource,basic,()->true,System::currentTimeMillis,3000);
		var wrapperRef=new AtomicReference<ServerControlledPlayer>();
		java.util.function.Consumer<String> remove=reason->{ service.dismiss(owner,reason); scheduler.unregister(wrapperRef.get()); };
		var controller=new CompanionController(context,movement,new CompanionFollowSettings(6,3,2,400,1000),remove,System::currentTimeMillis,
			()->{ movement.poll(); combat.tick(); });
		var ticks=new AtomicInteger();
		ServerPlayerController counted=new ServerPlayerController() {
			public void tick() { ticks.incrementAndGet(); controller.tick(); }
			public void stop() { controller.stop(); }
		};
		var wrapper=new ServerControlledPlayer(body,claim.databaseName(),blueprint.displayName(),SyntheticPlayerRole.COMPANION,
			sessions.guard(lease,counted,()->wrapperRef.get().isActive()),()->AIConfig.COMPANIONS_ENABLED && sessions.usable(lease),remove);
		wrapperRef.set(wrapper);
		var checkpoint=new BodyCheckpoint<CompanionBodyDAO.Snapshot>(true); checkpoint.activate();
		// Build the real private session record as a test fixture. All commands/dismiss/checkpoint
		// below invoke the production service; no production login validation is weakened.
		var type=Class.forName(CompanionService.class.getName()+"$CompanionSession");
		var constructor=type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
		Object session=constructor.newInstance(owner,wrapper,controller,new CompanionGoalSession(owner.getObjectId(),body.getObjectId()),execution,
			lease.generation(),combat,new CompanionPersistenceRuntime(),lease,movement,checkpoint,null,claim.token());
		sessions.publish(lease,session);
		SyntheticPlayerRuntime.getInstance().getRegistry().register(wrapper);
		CombatContributionOwnerResolver.getInstance().register(body,owner,lease.generation()); wrapper.markRegistered(); wrapper.activate(); scheduler.register(wrapper);
		return new Actor(owner,body,wrapper,ticks,connected);
	}
	static void load() throws Exception {
		check(CompanionC3RuntimeProbe.scalar("SELECT COUNT(*) FROM inventory WHERE item_unique_id=990003")==0,
			"deleted rich-source fixture cannot leak items into newly allocated owner IDs");
		AIConfig.ENABLED=true; AIConfig.COMPANIONS_ENABLED=true; AIConfig.COMPANION_ACTIVITIES_ENABLED=true;
		AIConfig.COMPANION_QUEST_GOALS_ENABLED=true; AIConfig.COMPANION_QUEST_EXECUTION_ENABLED=true;
		AIConfig.COMPANION_QUEST_GOAL_ALLOWED_IDS="1102,1120"; AIConfig.COMPANION_QUEST_EXECUTION_ALLOWED_IDS="1102,1120";
		AIConfig.COMPANION_GOAL_PERSISTENCE_ENABLED=true; AIConfig.COMPANION_CREATION_ENABLED=true;
		AIConfig.COMPANION_COMBAT_ENABLED=true; AIConfig.COMPANION_BASIC_ATTACK_ENABLED=true; AIConfig.COMPANION_OWNER_ATTRIBUTION_ENABLED=true;
		AIConfig.COMPANION_COMBAT_ALLOWED_NPC_IDS="210115";
		var service=CompanionService.getInstance(); int first=0;
		for(int count:new int[]{0,2,10}) {
			AIConfig.COMPANIONS_ENABLED=true;
			var scheduler=new SyntheticPlayerScheduler(()->1,()->2_000_000,System::nanoTime,false);
			var actors=new ArrayList<Actor>();
			for(int i=0;i<count;i++) actors.add(actor(first+i,service,scheduler)); first+=count;
			if(count>0) {
				Actor a=actors.getFirst();
				check(service.activities(a.owner()).stream().anyMatch(line->line.contains("1120")),"production activity catalog with native data");
				service.activity(a.owner(),"quest",1120);
				check(service.nextActivity(a.owner()).contains("примите"),"production next reads absent native quest without accepting it");
				check(a.owner().getQuestStateList().getQuestState(1120)==null,"activity choice does not accept quest");
				check(!service.nextActivity(actors.get(1).owner()).contains("примите"),"second owner retains independent activity");
				service.activity(a.owner(),"support",0); check(service.getCombatStatus(a.owner()).contains("assistEnabled=true"),"support enables owner assist");
				service.activity(a.owner(),"rest",0); check(service.getCombatStatus(a.owner()).contains("assistEnabled=false"),"rest disarms assist");
				service.activity(a.owner(),"escort",0);
				Player stale=CompanionC3RuntimeProbe.load(a.owner().getAccount().getId(),a.owner().getAccount().getName(),a.owner().getObjectId(),false);
				CompanionC3RuntimeProbe.rejected(()->service.nextActivity(stale),"relogged owner object cannot use old session"); CompanionC3RuntimeProbe.close(stale);
				var activeSessions=sessions(service);
				if(count==10) CompanionC3RuntimeProbe.rejected(()->activeSessions.reserve(Integer.MAX_VALUE,a.owner(),10),"capacity guard");
			}
			long[] durations=new long[120]; long started=System.nanoTime();
			for(int pass=0;pass<durations.length;pass++) { scheduler.runOnce(); durations[pass]=scheduler.getLastTickNanos(); if(count>0)Thread.sleep(200); }
			Arrays.sort(durations);
			long heap=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
			System.out.println("C8_LOAD actors="+count+" passes="+durations.length+" p50_us="+durations[60]/1000+" p95_us="+durations[114]/1000
				+" max_us="+scheduler.getMaxTickNanos()/1000+" budget_misses="+scheduler.getBudgetMissCount()+" elapsed_ms="+(System.nanoTime()-started)/1_000_000+" process_heap_bytes="+heap);
			for(Actor a:actors) check(a.ticks().get()>0 && a.body().getClientConnection()==null,"every actor scheduled without fake connection");
			if(count==2) {
				Actor a=actors.getFirst(); int bodyId=a.body().getObjectId();
				CompanionC3RuntimeProbe.fixture("CREATE TRIGGER c8_fail_checkpoint BEFORE UPDATE ON players FOR EACH ROW BEGIN IF NEW.id="+bodyId+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C8 injected checkpoint failure'; END IF; END");
				try { service.dismiss(a.owner(),"c8-sql-failure"); throw new AssertionError("Injected checkpoint error absent"); }
				catch(RuntimeException failure) { check(causedBy(failure,"C8 injected checkpoint failure"),"exact injected SQL failure reaches production cleanup"); }
				check(sessions(service).pendingRemoval(a.owner().getObjectId())!=null && !World.getInstance().isInWorld(bodyId),"failed checkpoint body absent, lease reserved");
				check(World.getInstance().isInWorld(actors.get(1).body().getObjectId()),"other owner unaffected by failed cleanup");
				CompanionC3RuntimeProbe.fixture("DROP TRIGGER c8_fail_checkpoint");
				check(service.dismiss(a.owner(),"c8-checkpoint-retry"),"production cleanup retries frozen snapshot"); scheduler.unregister(a.wrapper());
			}
			AIConfig.COMPANIONS_ENABLED=false;
			for(int pass=0;pass<100 && sessions(service).size()>0;pass++) { scheduler.runOnce(); Thread.sleep(20); }
			check(sessions(service).size()==0 && SyntheticPlayerRuntime.getInstance().getRegistry().isEmpty(),"flag rollback removes all sessions and runtime registrations");
			for(Actor a:actors) {
				check(!World.getInstance().isInWorld(a.body().getObjectId()) && !PlayerMoveTaskManager.getInstance().contains(a.body()) && !scheduler.contains(a.wrapper()),"no world/movement/scheduler body after rollback");
				World.getInstance().removeObject(a.owner()); CompanionC3RuntimeProbe.close(a.owner());
			}
			scheduler.shutdown();
		}
	}
	static void restoreLoad() throws Exception {
		check(CompanionC3RuntimeProbe.scalar("SELECT COUNT(*) FROM players WHERE account_id=880008")==12,"two plus ten distinct persistent bodies");
		var dao=new CompanionPersistenceDAO();
		for(int i=0;i<12;i++) {
			int ownerId=(int)CompanionC3RuntimeProbe.scalar("SELECT id FROM players WHERE name='Cloadowner"+(char)('a'+i)+"'");
			var claim=dao.loadCreationClaim(ownerId).orElseThrow();
			Player body=PlayerService.getServerControlledPlayer(claim.companionPlayerId(),AccountService.loadServerControlledAccount(880008,"ai_c8_host",claim.companionPlayerId()),claim.blueprint().advancedClass());
			CompanionBodyDAO.verifyLoaded(body);
			check(body.getLevel()==10 && body.getPlayerClass()==claim.blueprint().advancedClass() && body.getClientConnection()==null,"batch profile checkpoint restored after separate JVM");
			check(body.getQuestStateList().getAllQuestState().isEmpty(),"batch companion has no private quest journal"); CompanionC3RuntimeProbe.close(body);
		}
	}
}
