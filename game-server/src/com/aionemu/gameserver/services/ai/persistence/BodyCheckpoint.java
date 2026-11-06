package com.aionemu.gameserver.services.ai.persistence;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Retains the exact frozen snapshot until commit; cleanup retries cannot recapture changed state. */
public final class BodyCheckpoint<T> {

	private final boolean persistent;
	private T snapshot;
	private boolean activated;
	private boolean committed;

	public BodyCheckpoint(boolean persistent) { this.persistent = persistent; }
	public void activate() { activated = true; }
	public void save(Supplier<T> freeze, Consumer<T> store) {
		if (!persistent || !activated || committed) return;
		if (snapshot == null) snapshot = java.util.Objects.requireNonNull(freeze.get());
		store.accept(snapshot);
		committed = true;
	}
	public boolean isCommitted() { return committed; }
	public boolean isPending() { return snapshot != null && !committed; }
}
