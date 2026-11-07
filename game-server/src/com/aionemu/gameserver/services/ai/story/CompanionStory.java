package com.aionemu.gameserver.services.ai.story;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import com.aionemu.gameserver.services.ai.memory.QuestCompletionEvidence;
import com.aionemu.gameserver.services.ai.quest.QuestGoalPlan;

/** P1 has one curated suggestion. It stores a conversation decision, never native progress or a second goal intent. */
public final class CompanionStory {
	public static final int QUEST = 1120;
	private CompanionStory() { }
	public record Owner(String realm, int playerId, int accountId) {
		public Owner {
			if (!QuestCompletionEvidence.validRealm(realm) || playerId <= 0 || accountId <= 0)
				throw new IllegalArgumentException("Invalid story owner");
		}
	}
	public record Actor(Owner owner, int bodyId, UUID claim) {
		public Actor {
			Objects.requireNonNull(owner); Objects.requireNonNull(claim);
			if (bodyId <= 0 || bodyId == owner.playerId()) throw new IllegalArgumentException("Invalid story body");
		}
	}
	public record Episode(UUID event, int bodyId, UUID claim, long completedSecond) {
		public Episode {
			Objects.requireNonNull(event); Objects.requireNonNull(claim);
			if (bodyId <= 0 || completedSecond <= 0) throw new IllegalArgumentException("Invalid shared episode");
		}
		public boolean belongsTo(Actor actor) { return bodyId == actor.bodyId() && claim.equals(actor.claim()); }
	}
	public enum Decision { OFFERED, DECLINED, RESET }
	public record Offer(UUID token, UUID event, int bodyId, UUID claim, String fingerprint, Decision decision, long revision) {
		public Offer {
			Objects.requireNonNull(token); Objects.requireNonNull(event); Objects.requireNonNull(claim); Objects.requireNonNull(decision);
			if (bodyId <= 0 || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}") || revision <= 0)
				throw new IllegalArgumentException("Invalid story offer");
		}
		public boolean matches(Episode episode) {
			return event.equals(episode.event()) && bodyId == episode.bodyId() && claim.equals(episode.claim());
		}
	}
	public record View(Episode episode, Offer offer) {
		public View {
			Objects.requireNonNull(episode);
			if (offer != null && !offer.matches(episode)) throw new IllegalStateException("Story identity/source conflict");
		}
		public boolean declined() { return offer != null && offer.decision() == Decision.DECLINED; }
	}
	public record Candidate(QuestGoalPlan plan, String unavailableReason) {
		public Candidate {
			if (plan != null && plan.questId() != QUEST || plan == null && (unavailableReason == null || unavailableReason.isBlank()))
				throw new IllegalArgumentException("Invalid story candidate");
		}
		public static Candidate unavailable(String reason) { return new Candidate(null, reason); }
		public static Candidate available(QuestGoalPlan plan) { return new Candidate(Objects.requireNonNull(plan), null); }
	}
	public enum Kind { DISABLED, NO_MEMORY, UNAVAILABLE, READY, OFFERED, DECLINED, RESET, STALE }
	public record Reply(Kind kind, UUID token, List<String> lines) {
		public Reply { lines = List.copyOf(lines); }
		public static Reply of(Kind kind, UUID token, String... lines) { return new Reply(kind, token, List.of(lines)); }
	}
}
