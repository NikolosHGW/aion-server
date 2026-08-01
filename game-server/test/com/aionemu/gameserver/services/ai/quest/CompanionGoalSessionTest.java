package com.aionemu.gameserver.services.ai.quest;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.services.ai.quest.CompanionGoalSession.ChoiceResult;
import com.aionemu.gameserver.services.ai.quest.CompanionGoalSession.ChoiceStatus;

class CompanionGoalSessionTest {

	@Test
	void todayThenImmediateChooseStoresTheRevalidatedRuntimePlan() {
		CompanionGoalSession session = new CompanionGoalSession(10, 20);
		QuestGoalPlan offered = plan(1102, 3);
		QuestGoalPlan rebuilt = plan(1102, 3);

		session.offer(offered);
		ChoiceResult result = session.choose(10, 20, rebuilt);

		assertEquals(ChoiceStatus.CHOSEN, result.status());
		assertSame(rebuilt, result.plan());
		assertSame(rebuilt, session.chosen());
		assertEquals(offered, session.offered());
	}

	@Test
	void repeatedTodayThenChooseUsesTheMostRecentOffer() {
		CompanionGoalSession session = new CompanionGoalSession(10, 20);
		session.offer(plan(1102, 3));
		QuestGoalPlan secondOffer = dynamicPlan("second offer", 25, 203057, 2, 4, 5, 6);
		session.offer(secondOffer);

		ChoiceResult result = session.choose(10, 20, dynamicPlan("rebuilt", 30, 203057, 3, 7, 8, 9));

		assertEquals(ChoiceStatus.CHOSEN, result.status());
		assertEquals(1102, result.questId());
	}

	@Test
	void valueEquivalentDistinctPlansAreNotStale() {
		CompanionGoalSession session = new CompanionGoalSession(10, 20);
		QuestGoalPlan offered = plan(1102, 3);
		QuestGoalPlan rebuilt = plan(1102, 3);
		assertNotSame(offered, rebuilt);
		assertEquals(offered, rebuilt);

		session.offer(offered);

		assertEquals(ChoiceStatus.CHOSEN, session.choose(10, 20, rebuilt).status());
	}

	@Test
	void movementReasonFormatterTextAndNearestReferenceSpawnDoNotMakeOfferStale() {
		CompanionGoalSession session = new CompanionGoalSession(10, 20);
		QuestGoalPlan offered = dynamicPlan(
			"full silent eligibility passed; current non-instance map; deterministic level/distance/count/questId score", 9, 203057, 1, 1, 2, 3);
		QuestGoalPlan rebuilt = new QuestGoalPlan(1102, "changed raw text", "changed localized text", "validated allowlist candidate", 1, 500,
			List.of(1101),
			List.of(new QuestGoalStep(QuestGoalStep.Type.KILL_NPC_SET, List.of(210133), List.of("changed formatter text"), 3,
				new QuestGoalStep.ReferenceCoordinate(210010000, 210133, 99, 101, 102, 103))),
			List.of("changed descriptive reward formatter text"), List.of("changed limitation formatter text"));

		session.offer(offered);
		ChoiceResult result = session.choose(10, 20, rebuilt);

		assertEquals(ChoiceStatus.CHOSEN, result.status());
		assertSame(rebuilt, session.chosen(), "The current, safely revalidated plan must become chosen");
	}

	@Test
	void changedStableQuestSemanticsAreRejectedAndCleared() {
		CompanionGoalSession session = new CompanionGoalSession(10, 20);
		session.offer(plan(1102, 3));

		ChoiceResult result = session.choose(10, 20, plan(1102, 4));

		assertEquals(ChoiceStatus.STALE_OFFER, result.status());
		assertEquals(1102, result.questId());
		assertEquals(QuestGoalReason.SEMANTIC_FINGERPRINT_CHANGED, result.reason());
		assertNull(session.offered());
		assertNull(session.chosen());
	}

	@Test
	void ownerAcceptingTheQuestAfterOfferReturnsStaleWithoutException() {
		CompanionGoalSession session = new CompanionGoalSession(10, 20);
		session.offer(plan(1102, 3));

		ChoiceResult result = assertDoesNotThrow(
			() -> session.revalidationFailed(10, 20, QuestGoalReason.INELIGIBLE_ALREADY_ACTIVE));

		assertEquals(ChoiceStatus.STALE_OFFER, result.status());
		assertEquals(QuestGoalReason.INELIGIBLE_ALREADY_ACTIVE, result.reason());
		assertNull(session.offered());
		assertNull(session.chosen());
	}

	@Test
	void chooseWithoutOfferReturnsNoOfferWithoutException() {
		CompanionGoalSession session = new CompanionGoalSession(10, 20);

		ChoiceResult result = assertDoesNotThrow(() -> session.choose(10, 20, null));

		assertEquals(ChoiceStatus.NO_OFFER, result.status());
		assertEquals(QuestGoalReason.NO_OFFER, result.reason());
	}

	@Test
	void ownerOrCompanionMismatchIsFailClosed() {
		CompanionGoalSession session = new CompanionGoalSession(10, 20);
		session.offer(plan(1102, 3));

		ChoiceResult result = assertDoesNotThrow(() -> session.choose(11, 20, plan(1102, 3)));

		assertEquals(ChoiceStatus.SESSION_MISMATCH, result.status());
		assertEquals(QuestGoalReason.SESSION_MISMATCH, result.reason());
		assertNull(session.offered());
		assertNull(session.chosen());

		session.offer(plan(1102, 3));
		assertEquals(ChoiceStatus.SESSION_MISMATCH, session.choose(10, 21, plan(1102, 3)).status());
		assertNull(session.offered());
	}

	@Test
	void clearIsIdempotentAndANewRuntimeBodyCannotInheritThePreviousSession() {
		CompanionGoalSession oldSession = new CompanionGoalSession(10, 20);
		oldSession.offer(plan(1102, 3));
		oldSession.choose(10, 20, plan(1102, 3));
		assertTrue(oldSession.clear());
		assertFalse(oldSession.clear());

		CompanionGoalSession replacement = new CompanionGoalSession(10, 21);
		assertNull(replacement.offered());
		assertNull(replacement.chosen());
		assertEquals(21, replacement.companionObjectId());
	}

	static QuestGoalPlan plan(int questId, int killCount) {
		return new QuestGoalPlan(questId, "Quest", "Quest", "reason", 1, 9, List.of(1101),
			List.of(new QuestGoalStep(QuestGoalStep.Type.KILL_NPC_SET, List.of(210133), List.of("Kerub"), killCount,
				new QuestGoalStep.ReferenceCoordinate(210010000, 210133, 1, 1, 2, 3))),
			List.of("exp=180"), List.of("reference only"));
	}

	private static QuestGoalPlan dynamicPlan(String reason, double distance, int referenceNpcId, int staticId, float x, float y, float z) {
		return new QuestGoalPlan(1102, "Quest", "Quest", reason, 1, distance, List.of(1101),
			List.of(new QuestGoalStep(QuestGoalStep.Type.KILL_NPC_SET, List.of(210133), List.of("Kerub"), 3,
				new QuestGoalStep.ReferenceCoordinate(210010000, referenceNpcId, staticId, x, y, z))),
			List.of("exp=180"), List.of("reference only"));
	}
}
