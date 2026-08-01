package com.aionemu.gameserver.services.ai.quest;

public final class CompanionGoalSession {

	private final int ownerObjectId;
	private final int companionObjectId;
	private QuestGoalPlan offered;
	private QuestGoalPlan chosen;

	public CompanionGoalSession(int ownerObjectId, int companionObjectId) {
		if (ownerObjectId <= 0 || companionObjectId <= 0)
			throw new IllegalArgumentException("Goal session identities must be positive");
		this.ownerObjectId = ownerObjectId;
		this.companionObjectId = companionObjectId;
	}

	public synchronized void offer(QuestGoalPlan plan) {
		offered = plan;
		chosen = null;
	}

	public synchronized ChoiceResult choose(int requesterObjectId, int currentCompanionObjectId, QuestGoalPlan rebuilt) {
		if (!matches(requesterObjectId, currentCompanionObjectId))
			return sessionMismatch();
		if (offered == null)
			return ChoiceResult.noOffer();
		if (rebuilt == null)
			throw new IllegalArgumentException("Revalidated plan must not be null");
		if (!offered.semanticFingerprint().equals(rebuilt.semanticFingerprint())) {
			int questId = offered.questId();
			clear();
			return ChoiceResult.stale(questId, QuestGoalReason.SEMANTIC_FINGERPRINT_CHANGED);
		}
		chosen = rebuilt;
		return ChoiceResult.chosen(chosen);
	}

	public synchronized ChoiceResult revalidationFailed(int requesterObjectId, int currentCompanionObjectId, QuestGoalReason reason) {
		if (!matches(requesterObjectId, currentCompanionObjectId))
			return sessionMismatch();
		if (offered == null)
			return ChoiceResult.noOffer();
		int questId = offered.questId();
		clear();
		return ChoiceResult.stale(questId, reason);
	}

	public synchronized ChoiceResult rejectSessionMismatch() {
		return sessionMismatch();
	}

	private boolean matches(int requesterObjectId, int currentCompanionObjectId) {
		return ownerObjectId == requesterObjectId && companionObjectId == currentCompanionObjectId;
	}

	private ChoiceResult sessionMismatch() {
		clear();
		return ChoiceResult.sessionMismatch();
	}

	public synchronized boolean clear() {
		boolean changed = offered != null || chosen != null;
		offered = null;
		chosen = null;
		return changed;
	}

	public int ownerObjectId() {
		return ownerObjectId;
	}

	public int companionObjectId() {
		return companionObjectId;
	}

	public synchronized QuestGoalPlan offered() {
		return offered;
	}

	public synchronized QuestGoalPlan chosen() {
		return chosen;
	}

	public synchronized String status() {
		return "goalSessionOwnerObjectId=" + ownerObjectId + ", goalSessionCompanionObjectId=" + companionObjectId + ", offeredQuestId="
			+ (offered == null ? "none" : offered.questId()) + ", chosenQuestId=" + (chosen == null ? "none" : chosen.questId());
	}

	public enum ChoiceStatus {
		CHOSEN,
		NO_OFFER,
		STALE_OFFER,
		INELIGIBLE,
		SESSION_MISMATCH
	}

	public record ChoiceResult(ChoiceStatus status, int questId, QuestGoalPlan plan, QuestGoalReason reason) {

		public static ChoiceResult chosen(QuestGoalPlan plan) {
			return new ChoiceResult(ChoiceStatus.CHOSEN, plan.questId(), plan, QuestGoalReason.ELIGIBLE);
		}

		public static ChoiceResult noOffer() {
			return new ChoiceResult(ChoiceStatus.NO_OFFER, 0, null, QuestGoalReason.NO_OFFER);
		}

		public static ChoiceResult stale(int questId, QuestGoalReason reason) {
			return new ChoiceResult(ChoiceStatus.STALE_OFFER, questId, null, reason);
		}

		public static ChoiceResult ineligible(QuestGoalReason reason) {
			return new ChoiceResult(ChoiceStatus.INELIGIBLE, 0, null, reason);
		}

		public static ChoiceResult sessionMismatch() {
			return new ChoiceResult(ChoiceStatus.SESSION_MISMATCH, 0, null, QuestGoalReason.SESSION_MISMATCH);
		}
	}
}
