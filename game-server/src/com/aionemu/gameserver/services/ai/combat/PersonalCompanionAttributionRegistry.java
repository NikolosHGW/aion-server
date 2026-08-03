package com.aionemu.gameserver.services.ai.combat;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;

import com.aionemu.gameserver.services.ai.SyntheticPlayerRole;

public final class PersonalCompanionAttributionRegistry<T> {

	public enum Lifecycle {
		ACTIVE,
		REMOVING
	}

	public record Mapping<T>(T companion, T owner, long sessionId, SyntheticPlayerRole role, Lifecycle lifecycle) {
	}

	private final Map<T, Mapping<T>> mappings = new IdentityHashMap<>();

	public synchronized void register(T companion, T owner, long sessionId, SyntheticPlayerRole role) {
		if (companion == null || owner == null || companion == owner)
			throw new IllegalArgumentException("Companion attribution requires distinct non-null identities");
		if (sessionId <= 0 || role != SyntheticPlayerRole.COMPANION)
			throw new IllegalArgumentException("Invalid personal companion attribution identity");
		if (mappings.containsKey(companion))
			throw new IllegalStateException("Companion attribution is already registered");
		mappings.put(companion, new Mapping<>(companion, owner, sessionId, role, Lifecycle.ACTIVE));
	}

	public synchronized boolean beginRemoval(T companion, long sessionId) {
		Mapping<T> mapping = mappings.get(companion);
		if (mapping == null || mapping.sessionId() != sessionId)
			return false;
		if (mapping.lifecycle() == Lifecycle.REMOVING)
			return false;
		mappings.put(companion,
			new Mapping<>(mapping.companion(), mapping.owner(), mapping.sessionId(), mapping.role(), Lifecycle.REMOVING));
		return true;
	}

	public synchronized void unregister(T companion, long sessionId) {
		mappings.computeIfPresent(companion, (_, mapping) -> mapping.sessionId() == sessionId ? null : mapping);
	}

	public synchronized Mapping<T> get(T companion) {
		return mappings.get(companion);
	}

	public synchronized boolean matches(T companion, T owner, long sessionId, boolean requireActive) {
		Mapping<T> mapping = mappings.get(companion);
		return mapping != null && mapping.companion() == companion && mapping.owner() == owner && mapping.sessionId() == sessionId
			&& mapping.role() == SyntheticPlayerRole.COMPANION && (!requireActive || mapping.lifecycle() == Lifecycle.ACTIVE);
	}

	public synchronized T resolveOrDefault(T attacker, Function<T, T> defaultOwner) {
		Mapping<T> mapping = mappings.get(attacker);
		return mapping != null && mapping.role() == SyntheticPlayerRole.COMPANION ? mapping.owner() : defaultOwner.apply(attacker);
	}

	public synchronized boolean contains(T companion) {
		return mappings.containsKey(companion);
	}
}
