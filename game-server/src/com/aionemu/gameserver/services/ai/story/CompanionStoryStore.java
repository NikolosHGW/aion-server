package com.aionemu.gameserver.services.ai.story;

import java.util.Optional;
import java.util.UUID;
import static com.aionemu.gameserver.services.ai.story.CompanionStory.*;

public interface CompanionStoryStore {
	Optional<View> read(Owner owner) throws Exception;
	Offer offer(Actor actor, Episode expected, String fingerprint) throws Exception;
	/** Empty means absent/stale token. A retry of the same decision is a no-op. */
	Optional<Offer> decide(Actor actor, UUID token, Decision decision) throws Exception;
}
