package com.aionemu.gameserver.services.ai.quest;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

class QuestGoalPlannerTest {

	@Test
	void selectionIsIndependentOfCandidateIterationOrder() {
		QuestGoalPlan near = plan(1103, 5, 4, 3);
		QuestGoalPlan far = plan(1102, 5, 100, 1);

		assertEquals(near, QuestGoalPlanner.selectDeterministically(5, List.of(far, near)).orElseThrow());
		assertEquals(near, QuestGoalPlanner.selectDeterministically(5, new HashSet<>(List.of(near, far))).orElseThrow());
	}

	@Test
	void questIdIsTheFinalStableTieBreaker() {
		QuestGoalPlan higherId = plan(1103, 5, 4, 3);
		QuestGoalPlan lowerId = plan(1102, 5, 4, 3);

		assertEquals(1102, QuestGoalPlanner.selectDeterministically(5, List.of(higherId, lowerId)).orElseThrow().questId());
	}

	@Test
	void lowerLevelDifferenceWinsBeforeDistanceAndObjectiveCount() {
		QuestGoalPlan sameLevel = plan(1103, 10, 1000, 20);
		QuestGoalPlan differentLevel = plan(1102, 9, 1, 1);

		assertEquals(sameLevel, QuestGoalPlanner.selectDeterministically(10, List.of(differentLevel, sameLevel)).orElseThrow());
	}

	@Test
	void proposalConsumesOnlyImmutableAssessmentsAndPreservesStableRejections() {
		QuestGoalPlanner planner = new QuestGoalPlanner();
		QuestGoalPlan accepted = plan(1102, 1, 4, 3);

		QuestGoalPlanner.ProposalResult result = planner.propose(1,
			List.of(QuestGoalPlanner.CandidateAssessment.reject(9999, QuestGoalReason.UNKNOWN_QUEST),
				QuestGoalPlanner.CandidateAssessment.accept(accepted)));

		assertTrue(result.success());
		assertEquals(1102, result.plan().questId());
		assertEquals(QuestGoalReason.UNKNOWN_QUEST, result.rejections().get(9999));
	}

	private QuestGoalPlan plan(int id, int minimumLevel, double distance, int count) {
		QuestGoalPlan base = CompanionGoalSessionTest.plan(id, count);
		return new QuestGoalPlan(id, base.rawName(), base.localizedQuestName(), base.selectionReason(), minimumLevel, distance,
			base.prerequisiteQuestIds(), base.steps(), base.rewardSummaries(), base.limitations());
	}
}
