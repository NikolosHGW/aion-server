package com.aionemu.gameserver.services.ai;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.gameserver.GameServer;
import com.aionemu.gameserver.configs.main.AIConfig;
import com.aionemu.gameserver.dao.*;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.questEngine.model.*;
import com.aionemu.gameserver.services.ai.memory.QuestCompletionEvidence;
import com.aionemu.gameserver.services.ai.story.*;
import com.aionemu.gameserver.world.World;
import static com.aionemu.gameserver.services.ai.story.CompanionStory.*;

/** Native commands/SQL with the C8 active-session fixture and explicit W0 evidence fixtures.
 * Does not invent a client connection or claim UI/login/witness acceptance. */
public final class CompanionP1RuntimeProbe {
	static final String REALM="p1_probe";
	static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); System.out.println("P1_CHECK PASS "+message); }
	static void sql(String query) throws Exception { CompanionC3RuntimeProbe.fixture(query); }
	static long number(String query) throws Exception { return CompanionC3RuntimeProbe.scalar(query); }
	static String value(String query) throws Exception {
		try(var c=DatabaseFactory.getConnection(); var s=c.createStatement(); var r=s.executeQuery(query)) {
			if(!r.next()) throw new AssertionError("Missing fixture row"); return r.getString(1);
		}
	}
	static Owner owner(Player player) { return new Owner(REALM,player.getObjectId(),player.getAccount().getId()); }
	static Actor actor(Player player) {
		var claim=new CompanionPersistenceDAO().loadCreationClaim(player.getObjectId()).orElseThrow();
		return new Actor(owner(player),claim.companionPlayerId(),claim.token());
	}
	static void flags() {
		AIConfig.ENABLED=AIConfig.SYNTHETIC_PLAYERS_ENABLED=AIConfig.COMPANIONS_ENABLED=true;
		AIConfig.COMPANION_PROFILE_ENABLED=AIConfig.COMPANION_CREATION_ENABLED=AIConfig.COMPANION_GOAL_PERSISTENCE_ENABLED=true;
		AIConfig.COMPANION_ACTIVITIES_ENABLED=AIConfig.COMPANION_QUEST_GOALS_ENABLED=AIConfig.COMPANION_QUEST_EXECUTION_ENABLED=true;
		AIConfig.WORLD_EVENTS_ENABLED=AIConfig.WORLD_MEMORY_ENABLED=AIConfig.COMPANION_STORY_ENABLED=true;
		AIConfig.WORLD_MEMORY_REALM=REALM;
		AIConfig.COMPANION_CREATION_HOST_ACCOUNT_ID=880008; AIConfig.COMPANION_CREATION_HOST_ACCOUNT_NAME="ai_c8_host";
		AIConfig.COMPANION_QUEST_GOAL_ALLOWED_IDS=AIConfig.COMPANION_QUEST_EXECUTION_ALLOWED_IDS="1102,1120";
	}
	public static void main(String[] args) throws Exception {
		var properties=new Properties();
		try(var input=Files.newInputStream(Path.of("config/mygs.properties"))) { properties.load(input); }
		if(!properties.getProperty("database.url","").startsWith("jdbc:mysql://127.0.0.1:53419/ai_p1_runtime?")
			|| args.length!=1 || !Set.of("exercise","restore").contains(args[0])) throw new IllegalStateException("P1 disposable database guard");
		try {
			var main=GameServer.class.getDeclaredMethod("main"); main.setAccessible(true); main.invoke(null);
			if(args[0].equals("exercise")) exercise(); else restore();
			System.out.println("P1_RUNTIME_PASS phase="+args[0]); GameServer.initShutdown(0,0);
		} catch(Throwable failure) { failure.printStackTrace(); System.err.println("P1_RUNTIME_FAIL"); GameServer.initShutdown(1,0); }
	}
	static String snapshot(String... tables) throws Exception {
		var result=new StringBuilder();
		for(String table:tables) try(var c=DatabaseFactory.getConnection(); var s=c.createStatement(); var r=s.executeQuery("SELECT * FROM "+table+" ORDER BY 1,2")) {
			while(r.next()) for(int i=1;i<=r.getMetaData().getColumnCount();i++) result.append(r.getString(i)).append('|');
		}
		return result.toString();
	}
	static String nativeSnapshot() throws Exception { return snapshot("players","inventory","player_quests","ai_companion_memories"); }
	static long revision(Player player) throws Exception { return number("SELECT revision FROM ai_companion_story WHERE owner_player_id="+player.getObjectId()); }
	static void memory(Player player,CompanionService service,boolean pendingTest) throws Exception {
		// Fixture at the W0 boundary: no fabricated claim that a real client completed this quest.
		var state=new QuestState(1102,QuestStatus.COMPLETE); player.getQuestStateList().addQuest(1102,state);
		var actor=actor(player); long now=System.currentTimeMillis();
		var evidence=new QuestCompletionEvidence(REALM,player.getObjectId(),actor.bodyId(),actor.claim(),1102,1,
			QuestCompletionEvidence.second(state.getLastCompleteTime()),now,1,210010000,1,3);
		var dao=new CompanionMemoryDAO(); dao.saveCandidate(evidence);
		if(pendingTest) {
			check(dao.reconcile(REALM,now,8)==0,"native row absent: W0 candidate not confirmed");
			check(service.storySuggest(player).kind()==Kind.NO_MEMORY,"P1 cannot use pending W0 evidence");
		}
		PlayerQuestListDAO.store(player);
		check(dao.reconcile(REALM,now+300000,8)==1,"W0 fixture confirmed only after native persistence");
	}
	static void exercise() throws Exception {
		var service=CompanionService.getInstance(); var store=new CompanionStoryDAO();
		var scheduler=new SyntheticPlayerScheduler(()->1,()->2_000_000,System::nanoTime,false);
		flags(); AIConfig.COMPANION_STORY_ENABLED=false;
		var a=CompanionC8RuntimeProbe.actor(0,service,scheduler); var b=CompanionC8RuntimeProbe.actor(1,service,scheduler);
		sql("RENAME TABLE ai_companion_story TO p1_hidden_story");
		check(service.storyStatus(a.owner()).kind()==Kind.DISABLED,"P1 off needs no story table");
		sql("RENAME TABLE p1_hidden_story TO ai_companion_story"); AIConfig.COMPANION_STORY_ENABLED=true;
		check(service.storySuggest(a.owner()).kind()==Kind.NO_MEMORY,"no history invented from ownership alone");
		memory(a.owner(),service,true); memory(b.owner(),service,false);
		check(number("SELECT COUNT(*) FROM ai_companion_story")==0,"reading confirmed history creates no suggestion");
		String initial=nativeSnapshot();
		Reply first=service.storySuggest(a.owner()); check(first.kind()==Kind.OFFERED,"real native candidate 1120 suggested");
		check(service.storySuggest(a.owner()).token().equals(first.token()) && revision(a.owner())==1,"repeat request reuses offer token and revision");
		check(service.storyDecision(b.owner(),first.token(),false).kind()==Kind.STALE,"foreign owner cannot answer another offer");
		check(store.read(new Owner(REALM,a.owner().getObjectId(),b.owner().getAccount().getId())).isEmpty(),"foreign account cannot read story");
		check(store.read(new Owner("different",a.owner().getObjectId(),a.owner().getAccount().getId())).isEmpty(),"story realm isolation");
		check(service.storyDecision(a.owner(),first.token(),false).kind()==Kind.DECLINED,"explicit refusal persisted");
		check(service.storyDecision(a.owner(),first.token(),false).kind()==Kind.DECLINED && revision(a.owner())==2,"duplicate refusal is idempotent");
		check(service.storySuggest(a.owner()).kind()==Kind.DECLINED,"refused suggestion is not offered again");
		check(service.proposeGoal(a.owner()).status()!=com.aionemu.gameserver.services.ai.quest.QuestGoalProposalResult.Status.OFFERED,"legacy today also honors refusal");
		check(service.storySuggest(b.owner()).kind()==Kind.OFFERED,"second owner still receives own suggestion");
		check(initial.equals(nativeSnapshot()),"story, status and refusal do not mutate native XP/items/kinah/quests or W0 evidence");
		service.activity(a.owner(),"quest",1120);
		check(service.getGoalStatus(a.owner()).contains("chosenQuestId=1120"),"explicit activity choice remains available after refusal");
		check(a.owner().getQuestStateList().getQuestState(1120)==null,"suggestion and explicit AI intent never auto-accept native quest");
		service.activity(a.owner(),"rest",0);
		check(service.getCombatStatus(a.owner()).contains("assistEnabled=false"),"owner can switch to rest without story enabling combat");
		check(service.storyDecision(a.owner(),first.token(),true).kind()==Kind.RESET,"owner can reset refusal");
		check(service.storyDecision(a.owner(),first.token(),false).kind()==Kind.STALE,"delayed refusal after reset rejected");
		check(service.storySuggest(a.owner()).kind()==Kind.READY,"suggestion does not replace an already chosen activity");
		check(service.getGoalStatus(a.owner()).contains("chosenQuestId=1120"),"reset keeps existing goal intent");
		service.clearGoal(a.owner());
		Reply second=service.storySuggest(a.owner()); check(second.kind()==Kind.OFFERED && !first.token().equals(second.token()),"new offer gets a new token after reset");
		check(service.storyDecision(a.owner(),first.token(),false).kind()==Kind.STALE,"old reply cannot decline a new offer");
		faults(a.owner(),b.owner(),service,store);
		Reply current=service.storyStatus(a.owner());
		check(service.storyDecision(a.owner(),current.token(),false).kind()==Kind.DECLINED,"A refusal prepared for restart");
		current=service.storyStatus(b.owner()); service.storyDecision(b.owner(),current.token(),true);
		String story=snapshot("ai_companion_story");
		for(int i=0;i<3;i++) { service.storyStatus(a.owner()); service.storyStatus(b.owner()); }
		check(story.equals(snapshot("ai_companion_story")),"status is read-only, including revision");
		check(number("SELECT COUNT(*) FROM player_quests WHERE player_id IN (SELECT companion_player_id FROM ai_companions)")==0,"no second quest journal");
		for(var fixture:List.of(a,b)) {
			service.dismiss(fixture.owner(),"p1-fixture-cleanup"); scheduler.unregister(fixture.wrapper());
			World.getInstance().removeObject(fixture.owner()); CompanionC3RuntimeProbe.close(fixture.owner());
		}
		scheduler.shutdown();
		check(service.storyStatus(a.owner()).kind()==Kind.DECLINED,"read-only story status survives dismiss");
		Files.writeString(Path.of("p1-native-snapshot.txt"),nativeSnapshot());
		Files.writeString(Path.of("p1-story-snapshot.txt"),snapshot("ai_companion_story"));
	}
	static void faults(Player a,Player b,CompanionService service,CompanionStoryDAO store) throws Exception {
		String baseline=nativeSnapshot(); var actor=actor(a); Reply current=service.storyStatus(a);
		long before=revision(a);
		sql("CREATE TRIGGER p1_fail BEFORE UPDATE ON ai_companion_story FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='P1 injected failure'");
		try { check(service.storyDecision(a,current.token(),false).kind()==Kind.UNAVAILABLE,"SQL failure never claims refusal saved"); }
		finally { sql("DROP TRIGGER p1_fail"); }
		check(revision(a)==before && service.storyStatus(a).kind()==Kind.OFFERED,"failed decision rolled back");
		sql("RENAME TABLE ai_companion_story TO p1_hidden_story");
		String goal=service.getGoalStatus(a);
		try {
			check(service.storySuggest(a).kind()==Kind.UNAVAILABLE,"story DB read failure is explicit");
			CompanionC3RuntimeProbe.rejected(()->service.proposeGoal(a),"today fails closed when preferences cannot be read");
			check(service.getGoalStatus(a).equals(goal),"failed preference read does not clear old goal");
		} finally { sql("RENAME TABLE p1_hidden_story TO ai_companion_story"); }
		AIConfig.COMPANION_QUEST_GOAL_ALLOWED_IDS=AIConfig.COMPANION_QUEST_EXECUTION_ALLOWED_IDS="1102";
		check(service.storySuggest(a).kind()==Kind.READY,"removed quest allowlist blocks new suggestion");
		AIConfig.COMPANION_QUEST_GOAL_ALLOWED_IDS=AIConfig.COMPANION_QUEST_EXECUTION_ALLOWED_IDS="1102,1120";
		AIConfig.COMPANION_STORY_ENABLED=false;
		check(service.storyDecision(a,current.token(),false).kind()==Kind.DISABLED,"P1 switch prevents writes");
		check(service.proposeGoal(a).questId()==1120,"legacy today still works with P1 off"); service.clearGoal(a);
		AIConfig.COMPANION_STORY_ENABLED=true;
		var context=store.read(owner(b)).orElseThrow(); var bActor=actor(b); long bBefore=revision(b);
		try(var threads=Executors.newFixedThreadPool(4)) {
			var tasks=new ArrayList<Callable<Offer>>();
			for(int i=0;i<8;i++) tasks.add(()->store.offer(bActor,context.episode(),context.offer().fingerprint()));
			for(var future:threads.invokeAll(tasks)) check(future.get(10,TimeUnit.SECONDS).token().equals(context.offer().token()),"parallel proposal reuses one token");
			var decisions=new ArrayList<Callable<Optional<Offer>>>();
			for(int i=0;i<8;i++) decisions.add(()->store.decide(bActor,context.offer().token(),Decision.DECLINED));
			for(var future:threads.invokeAll(decisions)) check(future.get(10,TimeUnit.SECONDS).orElseThrow().decision()==Decision.DECLINED,"parallel refusals idempotent");
		}
		check(revision(b)==bBefore+1,"one revision for eight concurrent refusals");
		String original=actor.claim().toString(); sql("UPDATE ai_companion_creation_claims SET claim_token='"+UUID.randomUUID()+"' WHERE owner_player_id="+a.getObjectId());
		try {
			check(service.storyStatus(a).kind()==Kind.NO_MEMORY,"replacement claim cannot inherit story");
			check(service.storyDecision(a,current.token(),false).kind()==Kind.STALE,"stale active body cannot write through new claim");
		} finally { sql("UPDATE ai_companion_creation_claims SET claim_token='"+original+"' WHERE owner_player_id="+a.getObjectId()); }
		check(baseline.equals(nativeSnapshot()),"P1 fault/retry/concurrency/reset leaves native tables and W0 unchanged");
	}
	static void restore() throws Exception {
		flags(); var service=CompanionService.getInstance();
		int aId=(int)number("SELECT id FROM players WHERE name='Cloadownera'");
		int bId=(int)number("SELECT id FROM players WHERE name='Cloadownerb'");
		Player a=CompanionC3RuntimeProbe.load(790100,"c8_owner_a",aId,false);
		Player b=CompanionC3RuntimeProbe.load(790101,"c8_owner_b",bId,false);
		check(service.storyStatus(a).kind()==Kind.DECLINED,"A refusal survives separate JVM without summon");
		check(service.storyStatus(b).kind()==Kind.RESET,"B reset is independent and survives restart");
		check(CompanionStoryService.getInstance().declinedQuests(actor(a)).equals(Set.of(1120)),"restored refusal suppresses future recommendations");
		check(CompanionStoryService.getInstance().declinedQuests(actor(b)).isEmpty(),"B preference is not inherited from A");
		check(Files.readString(Path.of("p1-story-snapshot.txt")).equals(snapshot("ai_companion_story")),"restart status leaves tokens/revisions unchanged");
		check(Files.readString(Path.of("p1-native-snapshot.txt")).equals(nativeSnapshot()),"restart reads do not mutate native state or W0 history");
		CompanionC3RuntimeProbe.close(a); CompanionC3RuntimeProbe.close(b);
	}
}
