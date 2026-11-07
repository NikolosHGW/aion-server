package com.aionemu.gameserver.services.ai.memory;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.gameserver.GameServer;
import com.aionemu.gameserver.configs.main.AIConfig;
import com.aionemu.gameserver.dao.*;
import com.aionemu.gameserver.model.gameobjects.Npc;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.spawns.*;
import com.aionemu.gameserver.questEngine.model.*;
import com.aionemu.gameserver.services.*;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.services.ai.creation.*;
import com.aionemu.gameserver.spawnengine.SpawnEngine;
import com.aionemu.gameserver.world.World;
import static com.aionemu.gameserver.model.DialogAction.SELECTED_QUEST_NOREWARD;

/** Native server/SQL tests. Evidence enqueue is an explicit witness-boundary fixture, NOT client acceptance.
 * No connection is fabricated and production capture continues to reject these disconnected actors. */
public final class CompanionW0RuntimeProbe {
	static final String REALM = "w0_probe";
	static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
		System.out.println("W0_CHECK PASS " + message);
	}
	static void sql(String query) throws SQLException {
		try (var c=DatabaseFactory.getConnection(); var s=c.createStatement()) { s.executeUpdate(query); }
	}
	static String value(String query) throws SQLException {
		try (var c=DatabaseFactory.getConnection(); var s=c.createStatement(); var r=s.executeQuery(query)) {
			if (!r.next()) throw new AssertionError("Missing fixture row: " + query); return r.getString(1);
		}
	}
	static long number(String query) throws SQLException { return Long.parseLong(value(query)); }
	public static void main(String[] args) throws Exception {
		var config=new Properties();
		try (var in=Files.newInputStream(Path.of("config/mygs.properties"))) { config.load(in); }
		if (!config.getProperty("database.url", "").startsWith("jdbc:mysql://127.0.0.1:53418/ai_w0_runtime?")
			|| args.length!=1 || !Set.of("exercise", "restore").contains(args[0])) throw new IllegalStateException("Disposable W0 database guard");
		try {
			var main=GameServer.class.getDeclaredMethod("main"); main.setAccessible(true); main.invoke(null);
			if (args[0].equals("exercise")) exercise(); else restore();
			System.out.println("W0_RUNTIME_PASS phase=" + args[0]); GameServer.initShutdown(0,0);
		} catch (Throwable failure) { failure.printStackTrace(); System.err.println("W0_RUNTIME_FAIL"); GameServer.initShutdown(1,0); }
	}
	static int id(String name) throws SQLException { return (int)number("SELECT id FROM players WHERE name='"+name+"'"); }
	static Player load(int account, String name) throws Exception {
		int id=id(name);
		return PlayerService.getServerControlledPlayer(id, AccountService.loadServerControlledAccount(account,"w0_owner_"+account,id));
	}
	static void create(int account, String name, String alias) {
		var gateway=new DefaultCompanionPlayerCreationGateway();
		var owner=gateway.create(new CompanionCreationRequest(account,"ELYOS","MALE"),name,account,"w0_owner_"+account);
		var blueprint=new CompanionSourceDAO().snapshot(account,name,alias,"gladiator","ELYOS");
		new CompanionCreationService(new CompanionPersistenceDAO(),gateway).create(
			new CompanionCreationRequest(owner.playerId(),"ELYOS","MALE",blueprint),880010,"ai_w0_host");
	}
	static QuestCompletionEvidence evidence(Player owner, String realm, long now) {
		var claim=new CompanionPersistenceDAO().loadCreationClaim(owner.getObjectId()).orElseThrow();
		var quest=owner.getQuestStateList().getQuestState(1102);
		return new QuestCompletionEvidence(realm,owner.getObjectId(),claim.companionPlayerId(),claim.token(),1102,1,
			QuestCompletionEvidence.second(quest.getLastCompleteTime()),now,1,210010000,1,3);
	}
	static QuestCompletionEvidence copy(QuestCompletionEvidence e, String realm, long second, UUID token) {
		return new QuestCompletionEvidence(realm,e.ownerId(),e.companionId(),token,1102,1,second,e.observedMillis(),1,210010000,1,3);
	}
	static String snapshot() throws Exception {
		var result=new StringBuilder();
		// Complete persisted native rows, not only totals; memory must never alter any of these.
		for (String query:List.of("SELECT * FROM players ORDER BY id", "SELECT * FROM inventory ORDER BY item_unique_id",
			"SELECT * FROM player_quests ORDER BY player_id,quest_id")) {
			try(var c=DatabaseFactory.getConnection(); var s=c.createStatement(); var r=s.executeQuery(query)) {
				while(r.next()) for(int i=1;i<=r.getMetaData().getColumnCount();i++) result.append(r.getString(i)).append('|');
			}
		}
		return result.toString();
	}
	static void exercise() throws Exception {
		var dao=new CompanionMemoryDAO();
		// Disabled startup/status works without a memory table and does not silently recreate it.
		sql("RENAME TABLE ai_companion_memories TO w0_hidden_memory");
		check(CompanionEpisodeService.getInstance().status(1,1).equals("memory=DISABLED"),"default off needs no memory table");
		sql("RENAME TABLE w0_hidden_memory TO ai_companion_memories");
		create(770101,"Wzeroalpha","Alpha"); create(770102,"Wzerobeta","Beta");
		Player a=load(770101,"Wzeroalpha"), b=load(770102,"Wzerobeta");
		Npc npc=(Npc)SpawnEngine.spawnObject(new SpawnTemplate(new SpawnGroup(210010000,203057,0,null),467,1669.97f,125.252f,(byte)79,0,null,0),1);
		for(Player owner:List.of(a,b)) {
			World.getInstance().setPosition(owner,210010000,1,466.5f,1669.97f,125.252f,(byte)79);
			World.getInstance().storeObject(owner); World.getInstance().spawn(owner); owner.getLifeStats().cancelAllTasks();
			// Pre-completion fixture only; actual reward and completion below run through QuestService.
			owner.getQuestStateList().addQuest(1102,new QuestState(1102,QuestStatus.REWARD)); PlayerQuestListDAO.store(owner);
			check(QuestService.finishQuest(new QuestEnv(npc,owner,1102,SELECTED_QUEST_NOREWARD)),"native finishQuest rewards and completes " + owner.getObjectId());
			check(CompanionEpisodeService.sourceEligible(1102,0,owner.getQuestStateList().getQuestState(1102),203057,SELECTED_QUEST_NOREWARD),"native completion qualifies as source");
			check(!QuestService.finishQuest(new QuestEnv(npc,owner,1102,SELECTED_QUEST_NOREWARD)),"native repeated finish refuses reward replay");
		}
		check(number("SELECT COUNT(*) FROM ai_companion_memories")==0,"disconnected test actors do not create production witnesses");
		long now=System.currentTimeMillis(); var clock=new AtomicLong(now);
		var ea=evidence(a,REALM,now); var eb=evidence(b,REALM,now);
		try(var service=new CompanionEpisodeService(dao,()->true,REALM,clock::get,128)) {
			check(service.offer(ea) && service.offer(eb),"explicit immutable witness-boundary fixtures queued"); service.runOnce();
			check(dao.status(REALM,ea.ownerId(),770101).equals("memory=PENDING"),"in-memory COMPLETE is not durable native confirmation");
			check(number("SELECT COUNT(*) FROM ai_companion_memories WHERE projection_version=1")==0,"REWARD native rows produce no confirmed projection");
			PlayerQuestListDAO.store(a); PlayerQuestListDAO.store(b); PlayerDAO.storePlayer(a); PlayerDAO.storePlayer(b);
			// Persist native inventory through its normal DAO as part of the isolated test checkpoint.
			InventoryDAO.store(a); InventoryDAO.store(b);
			try(var c=DatabaseFactory.getConnection()) {
				check(NativeQuestCompletionReader.read(c,ea)==NativeQuestCompletionReader.Result.MATCH,"actual JDBC timestamp roundtrip agrees with captured seconds");
			}
			// Keep A pending until the next process; only B is due in this phase.
			sql("UPDATE ai_companion_memories SET next_attempt_millis="+now+" WHERE owner_player_id="+eb.ownerId());
			check(dao.reconcile(REALM,now,8)==1,"B confirmed; A durable candidate reserved for restart");
			check(dao.status(REALM,eb.ownerId(),770102).startsWith("memory=CONFIRMED"),"owner B sees personal confirmed memory");
			check(dao.status(REALM,eb.ownerId(),770101).equals("memory=EMPTY"),"foreign account cannot read B memory");
			check(dao.status("different_realm",eb.ownerId(),770102).equals("memory=EMPTY"),"realm isolation");
			String before=snapshot();
			faults(dao,ea,now);
			check(before.equals(snapshot()),"memory reads, retries, concurrency and failures do not mutate native XP/items/kinah/quests");
			check(number("SELECT COUNT(*) FROM player_quests WHERE player_id IN (SELECT companion_player_id FROM ai_companions)")==0,"no second companion quest journal");
			Files.writeString(Path.of("w0-native-snapshot.txt"),snapshot());
			Files.writeString(Path.of("w0-event-id.txt"),value("SELECT event_id FROM ai_companion_memories WHERE realm='"+REALM+"' AND owner_player_id="+eb.ownerId()));
		}
		for(Player actor:List.of(a,b)) { World.getInstance().removeObject(actor); actor.getLifeStats().cancelAllTasks(); actor.getEffectController().removeAllEffects(true); }
		World.getInstance().removeObject(npc);
	}
	static void faults(CompanionMemoryDAO dao, QuestCompletionEvidence e, long now) throws Exception {
		var retry=copy(e,"failure",e.completedSecond(),e.claimToken()); dao.saveCandidate(retry);
		sql("CREATE TRIGGER w0_fail BEFORE UPDATE ON ai_companion_memories FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='W0 injected failure'");
		try { dao.reconcile("failure",now,8); throw new AssertionError("SQL failure not propagated"); }
		catch(SQLException expected) { check(true,"failed SQL confirmation propagates instead of false success"); }
		finally { sql("DROP TRIGGER w0_fail"); }
		check(number("SELECT projection_version+attempts FROM ai_companion_memories WHERE realm='failure'")==0,"failed transaction rolls back event and projection together");
		check(dao.reconcile("failure",now,8)==1,"retry confirms once after DB recovery");
		var readFailure=copy(e,"read_failure",e.completedSecond(),e.claimToken()); dao.saveCandidate(readFailure);
		sql("RENAME TABLE player_quests TO w0_hidden_quests");
		try {
			try { dao.reconcile("read_failure",now,8); throw new AssertionError("Missing native source treated as successful read"); }
			catch(SQLException expected) { check(true,"native read SQL failure is not WAITING or COMPLETE"); }
		} finally { sql("RENAME TABLE w0_hidden_quests TO player_quests"); }
		check(number("SELECT projection_version+attempts FROM ai_companion_memories WHERE realm='read_failure'")==0,"native read failure leaves pending unchanged");
		check(dao.reconcile("read_failure",now,8)==1,"native reader recovers after SQL failure");
		var conflict=copy(e,"conflict",e.completedSecond(),e.claimToken()); dao.saveCandidate(conflict);
		check(!dao.saveCandidate(copy(conflict,"conflict",e.completedSecond()+1,e.claimToken())),"same source with conflicting payload rejected");
		check(dao.status("conflict",e.ownerId(),770101).equals("memory=QUARANTINED"),"payload conflict quarantined");
		var reset=copy(e,"reset",e.completedSecond()+1,e.claimToken()); dao.saveCandidate(reset); dao.reconcile("reset",now,8);
		check(dao.status("reset",e.ownerId(),770101).equals("memory=QUARANTINED"),"native occurrence mismatch quarantined");
		var expired=copy(e,"expired",e.completedSecond(),e.claimToken()); dao.saveCandidate(expired); dao.reconcile("expired",now+CompanionMemoryDAO.TTL_MILLIS,8);
		check(dao.status("expired",e.ownerId(),770101).equals("memory=EXPIRED"),"pending TTL bounded");
		var exhausted=copy(e,"exhausted",e.completedSecond(),e.claimToken()); dao.saveCandidate(exhausted);
		sql("UPDATE ai_companion_memories SET attempts="+CompanionMemoryDAO.MAX_ATTEMPTS+" WHERE realm='exhausted'");
		dao.reconcile("exhausted",now,8);
		check(dao.status("exhausted",e.ownerId(),770101).equals("memory=EXPIRED"),"native confirmation attempt budget bounded");
		var concurrent=copy(e,"concurrent",e.completedSecond(),e.claimToken());
		try(var threads=Executors.newFixedThreadPool(4)) {
			var tasks=new ArrayList<Callable<Boolean>>();
			for(int i=0;i<8;i++) tasks.add(()->{ boolean saved=dao.saveCandidate(concurrent); dao.reconcile("concurrent",now,8); return saved; });
			for(var result:threads.invokeAll(tasks)) check(result.get(10,TimeUnit.SECONDS),"concurrent duplicate accepted idempotently");
		}
		check(number("SELECT COUNT(*) FROM ai_companion_memories WHERE realm='concurrent' AND projection_version=1")==1,"concurrent delivery produces one event and version one");
		check(!dao.saveCandidate(copy(concurrent,"concurrent",e.completedSecond()+1,e.claimToken())),"confirmed source cannot be overwritten by new completion");
		check(dao.status("concurrent",e.ownerId(),770101).startsWith("memory=CONFIRMED"),"confirmed history preserved on conflict");
		// Fractional time and shared locks are measured against the real native schema/driver.
		try(var c=DatabaseFactory.getConnection()) {
			c.setAutoCommit(false);
			try(var s=c.prepareStatement("UPDATE player_quests SET complete_time=? WHERE player_id=? AND quest_id=1102")) {
				s.setTimestamp(1,new Timestamp(e.completedSecond()*1000+987)); s.setInt(2,e.ownerId()); s.executeUpdate();
			}
			check(NativeQuestCompletionReader.read(c,e)==NativeQuestCompletionReader.Result.MATCH,"TIMESTAMP(0) truncates .987 seconds with deployed driver/settings");
			c.rollback();
		}
		try(var locked=DatabaseFactory.getConnection(); var threads=Executors.newSingleThreadExecutor()) {
			locked.setAutoCommit(false); check(NativeQuestCompletionReader.read(locked,e)==NativeQuestCompletionReader.Result.MATCH,"native evidence shared locked");
			var started=new CountDownLatch(1);
			var writer=threads.submit(()->{ started.countDown(); sql("UPDATE player_quests SET quest_vars=quest_vars WHERE player_id="+e.ownerId()+" AND quest_id=1102"); return true; });
			check(started.await(5,TimeUnit.SECONDS),"concurrent native writer started");
			try { writer.get(150,TimeUnit.MILLISECONDS); throw new AssertionError("native evidence was not locked"); }
			catch(TimeoutException expected) { check(true,"native mutation waits until confirmation transaction releases source lock"); }
			finally { locked.rollback(); }
			check(writer.get(5,TimeUnit.SECONDS),"native writer resumes after release");
		}
		UUID replacement=UUID.randomUUID();
		sql("UPDATE ai_companion_creation_claims SET claim_token='"+replacement+"' WHERE owner_player_id="+e.ownerId());
		try {
			check(dao.status("concurrent",e.ownerId(),770101).equals("memory=EMPTY"),"replacement claim cannot inherit old memory");
			check(!dao.saveCandidate(copy(concurrent,"concurrent",e.completedSecond(),replacement)),"numeric ID reuse cannot replay retained source key");
			var stale=copy(e,"stale",e.completedSecond(),e.claimToken()); dao.saveCandidate(stale); dao.reconcile("stale",now,8);
			check(value("SELECT state FROM ai_companion_memories WHERE realm='stale'").equals("QUARANTINED"),"stale claim cannot confirm pending witness");
		} finally { sql("UPDATE ai_companion_creation_claims SET claim_token='"+e.claimToken()+"' WHERE owner_player_id="+e.ownerId()); }
	}
	static void restore() throws Exception {
		var dao=new CompanionMemoryDAO(); int a=id("Wzeroalpha"), b=id("Wzerobeta");
		check(dao.status(REALM,a,770101).equals("memory=PENDING"),"durable pending witness survives separate JVM");
		check(dao.status(REALM,b,770102).startsWith("memory=CONFIRMED"),"confirmed memory readable after restart without summoned body");
		check(value("SELECT event_id FROM ai_companion_memories WHERE realm='"+REALM+"' AND owner_player_id="+b)
			.equals(Files.readString(Path.of("w0-event-id.txt"))),"event ID stable across restart");
		var time=new AtomicLong(System.currentTimeMillis()+300000);
		try(var service=new CompanionEpisodeService(dao,()->true,REALM,time::get,128)) {
			service.runOnce(); check(service.status(a,770101).startsWith("memory=CONFIRMED"),"restart worker confirms committed native state");
			service.runOnce(); check(number("SELECT SUM(projection_version) FROM ai_companion_memories WHERE realm='"+REALM+"'")==2,"restart replay still exactly two personal projections");
		}
		check(Files.readString(Path.of("w0-native-snapshot.txt")).equals(snapshot()),"restart recovery does not change persisted native state");
		PlayerService.deletePlayerFromDB(b);
		check(number("SELECT COUNT(*) FROM ai_companion_memories WHERE realm='"+REALM+"' AND owner_player_id="+b)==1,"native owner deletion retains source tombstone");
		check(dao.status(REALM,b,770102).equals("memory=EMPTY"),"deleted owner cannot read retained history");
	}
}
