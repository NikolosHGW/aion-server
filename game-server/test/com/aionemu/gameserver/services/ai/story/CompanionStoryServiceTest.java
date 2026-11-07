package com.aionemu.gameserver.services.ai.story;

import static org.junit.jupiter.api.Assertions.*;
import static com.aionemu.gameserver.services.ai.story.CompanionStory.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import com.aionemu.gameserver.services.ai.quest.*;

class CompanionStoryServiceTest {
	private final Actor actor=new Actor(new Owner("test",1,10),2,UUID.randomUUID());
	private final Episode episode=new Episode(UUID.randomUUID(),2,actor.claim(),1234);
	private static QuestGoalPlan plan() {
		return new QuestGoalPlan(1120,"quest","quest","test",6,1,List.of(),List.of(new QuestGoalStep(
			QuestGoalStep.Type.KILL_NPC_SET,List.of(210142),List.of("target"),9,new QuestGoalStep.ReferenceCoordinate(210010000,210142,0,1,2,3))),List.of(),List.of());
	}
	private static class Store implements CompanionStoryStore {
		View view;
		int reads, offers, decisions;
		boolean failRead, failAfterOffer;
		public Optional<View> read(Owner owner) throws Exception { reads++; if(failRead) throw new Exception("read unavailable"); return Optional.ofNullable(view); }
		public Offer offer(Actor actor, Episode expected, String fingerprint) throws Exception {
			offers++;
			var result=new Offer(UUID.randomUUID(),expected.event(),expected.bodyId(),expected.claim(),fingerprint,Decision.OFFERED,1);
			view=new View(expected,result);
			if(failAfterOffer) throw new Exception("commit ack lost");
			return result;
		}
		public Optional<Offer> decide(Actor actor, UUID token, Decision decision) { decisions++; return Optional.empty(); }
	}
	@Test void noEpisodeMeansNoInventedHistoryOrCandidate() {
		var store=new Store(); var service=new CompanionStoryService(store,()->true);
		assertEquals(Kind.NO_MEMORY,service.suggest(actor,()->{fail("must not invent a suggestion without shared evidence"); return null;}).kind());
		assertEquals(0,store.offers); assertTrue(service.declinedQuests(actor).isEmpty());
	}
	@Test void disabledDoesNotNeedAnyTables() {
		var store=new Store(); var service=new CompanionStoryService(store,()->false);
		assertEquals(Kind.DISABLED,service.status(actor.owner()).kind());
		assertEquals(Kind.DISABLED,service.suggest(actor,()->Candidate.available(plan())).kind());
		assertEquals(Kind.DISABLED,service.decide(actor,UUID.randomUUID(),Decision.DECLINED).kind());
		assertTrue(service.declinedQuests(actor).isEmpty()); assertEquals(0,store.reads+store.offers+store.decisions);
	}
	@Test void statusAndIneligibleCandidateNeverWrite() {
		var store=new Store(); store.view=new View(episode,null); var service=new CompanionStoryService(store,()->true);
		assertEquals(Kind.READY,service.status(actor.owner()).kind());
		assertEquals(Kind.READY,service.suggest(actor,()->Candidate.unavailable("native eligibility failed")).kind());
		assertEquals(0,store.offers+store.decisions);
	}
	@Test void refusalSuppressesRecommenderAndIsNotAQuestAction() {
		var store=new Store(); var token=UUID.randomUUID();
		store.view=new View(episode,new Offer(token,episode.event(),2,actor.claim(),"a".repeat(64),Decision.DECLINED,2));
		var service=new CompanionStoryService(store,()->true);
		for(int i=0;i<5;i++) {
			assertEquals(Kind.DECLINED,service.suggest(actor,()->{fail("declined candidate should not be reconsidered"); return null;}).kind());
			assertEquals(Set.of(1120),service.declinedQuests(actor));
		}
		assertEquals(0,store.offers+store.decisions); assertEquals(token,service.status(actor.owner()).token());
	}
	@Test void currentClaimMustMatchBeforeAnyProposalOrMutation() {
		var store=new Store(); store.view=new View(episode,null); var service=new CompanionStoryService(store,()->true);
		var replaced=new Actor(actor.owner(),2,UUID.randomUUID());
		assertEquals(Kind.UNAVAILABLE,service.suggest(replaced,()->Candidate.available(plan())).kind());
		assertThrows(IllegalStateException.class,()->service.declinedQuests(replaced)); assertEquals(0,store.offers);
	}
	@Test void readFailureCannotBecomeEmptyPreferencesOrClearTheExistingGoal() {
		var store=new Store(); store.failRead=true; var service=new CompanionStoryService(store,()->true);
		assertEquals(Kind.UNAVAILABLE,service.status(actor.owner()).kind());
		assertEquals(Kind.UNAVAILABLE,service.suggest(actor,()->Candidate.available(plan())).kind());
		assertThrows(IllegalStateException.class,()->service.declinedQuests(actor)); assertEquals(0,store.offers+store.decisions);
	}
	@Test void ambiguousCommitAckDoesNotClaimSuccessAndStatusCanRecoverIt() {
		var store=new Store(); store.view=new View(episode,null); store.failAfterOffer=true;
		var service=new CompanionStoryService(store,()->true);
		assertEquals(Kind.UNAVAILABLE,service.suggest(actor,()->Candidate.available(plan())).kind());
		assertEquals(Kind.OFFERED,service.status(actor.owner()).kind()); assertEquals(1,store.offers);
	}
	@Test void rollbackFlagRecheckedBeforeMutation() {
		var store=new Store(); store.view=new View(episode,null); var enabled=new AtomicBoolean(true);
		var service=new CompanionStoryService(store,enabled::get);
		assertEquals(Kind.DISABLED,service.suggest(actor,()->{enabled.set(false); return Candidate.available(plan());}).kind());
		assertEquals(0,store.offers);
	}
	@Test void staleDecisionIsReportedAndWrongQuestCannotEnterP1() {
		var store=new Store(); var service=new CompanionStoryService(store,()->true);
		assertEquals(Kind.STALE,service.decide(actor,UUID.randomUUID(),Decision.DECLINED).kind());
		assertThrows(IllegalStateException.class,()->new View(episode,new Offer(UUID.randomUUID(),UUID.randomUUID(),2,actor.claim(),"a".repeat(64),Decision.OFFERED,1)));
		var p=plan();
		assertThrows(IllegalArgumentException.class,()->Candidate.available(new QuestGoalPlan(1102,p.rawName(),p.localizedQuestName(),p.selectionReason(),6,1,List.of(),p.steps(),List.of(),List.of())));
	}
}
