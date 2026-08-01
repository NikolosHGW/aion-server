package com.aionemu.gameserver.services.ai.quest;

import java.util.*;

public final class QuestGoalPlanner {

	public ProposalResult propose(int ownerLevel, Collection<CandidateAssessment> assessments) {
		if (assessments.isEmpty())
			return ProposalResult.reject(QuestGoalReason.INVALID_ALLOWLIST, Map.of());
		List<QuestGoalPlan> eligiblePlans = new ArrayList<>();
		Map<Integer, QuestGoalReason> rejections = new TreeMap<>();
		for (CandidateAssessment assessment : assessments.stream().sorted(Comparator.comparingInt(CandidateAssessment::questId)).toList()) {
			if (assessment.plan() == null)
				rejections.put(assessment.questId(), assessment.reason());
			else
				eligiblePlans.add(assessment.plan());
		}
		Optional<QuestGoalPlan> selected = selectDeterministically(ownerLevel, eligiblePlans);
		if (selected.isEmpty()) {
			QuestGoalReason reason = rejections.isEmpty() ? QuestGoalReason.NO_ELIGIBLE_CANDIDATE : rejections.values().iterator().next();
			return ProposalResult.reject(reason, rejections);
		}
		QuestGoalPlan plan = selected.get().withSelectionReason(
			"full silent eligibility passed; current non-instance map; deterministic level/distance/count/questId score");
		return ProposalResult.accept(plan, rejections);
	}

	public record CandidateAssessment(int questId, QuestGoalPlan plan, QuestGoalReason reason) {

		public CandidateAssessment {
			if (questId <= 0 || reason == null || plan != null && plan.questId() != questId)
				throw new IllegalArgumentException("Invalid quest candidate assessment");
		}

		public static CandidateAssessment accept(QuestGoalPlan plan) {
			return new CandidateAssessment(plan.questId(), plan, QuestGoalReason.ELIGIBLE);
		}

		public static CandidateAssessment reject(int questId, QuestGoalReason reason) {
			return new CandidateAssessment(questId, null, reason);
		}
	}

	public static Optional<QuestGoalPlan> selectDeterministically(int ownerLevel, Collection<QuestGoalPlan> plans) {
		Comparator<QuestGoalPlan> score = Comparator.comparingInt((QuestGoalPlan plan) -> Math.abs(ownerLevel - plan.minimumLevel()))
			.thenComparingDouble(QuestGoalPlan::distanceToStartSquared).thenComparingInt(QuestGoalPlan::totalObjectiveCount)
			.thenComparingInt(QuestGoalPlan::questId);
		return plans.stream().min(score);
	}

	public record ProposalResult(QuestGoalPlan plan, QuestGoalReason reason, Map<Integer, QuestGoalReason> rejections) {

		public ProposalResult {
			rejections = Map.copyOf(rejections);
		}

		public static ProposalResult accept(QuestGoalPlan plan, Map<Integer, QuestGoalReason> rejections) {
			return new ProposalResult(plan, QuestGoalReason.ELIGIBLE, rejections);
		}

		public static ProposalResult reject(QuestGoalReason reason, Map<Integer, QuestGoalReason> rejections) {
			return new ProposalResult(null, reason, rejections);
		}

		public boolean success() {
			return plan != null;
		}
	}
}
