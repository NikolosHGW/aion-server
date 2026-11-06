package com.aionemu.gameserver.services.ai.action;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Local execution receipts, never an authorization to bypass a native gateway. */
public final class ActionSession {

	public enum State {
		ACCEPTED, RUNNING, SUCCEEDED, REJECTED, CANCELLED, FAILED;
		public boolean terminal() { return this != ACCEPTED && this != RUNNING; }
	}
	public record Id(UUID session, long sequence) { }
	public record Receipt(Id id, String intent, State state, String reason, long deadline) { }
	public record Outcome(State state, String reason) {
		public Outcome {
			Objects.requireNonNull(state);
			Objects.requireNonNull(reason);
			if (state != State.RUNNING && state != State.SUCCEEDED && state != State.REJECTED)
				throw new IllegalArgumentException("Invalid execution outcome");
		}
	}
	private final UUID incarnation = UUID.randomUUID();
	private final BooleanSupplier usable;
	private final LongSupplier clock;
	private final Map<Long, Receipt> history = new LinkedHashMap<>();
	private final int capacity;
	private long issued;
	private long consumed;
	private Receipt active;
	private Consumer<String> stop;
	private boolean closed;

	public ActionSession(BooleanSupplier usable, LongSupplier clock, int capacity) {
		if (capacity < 1) throw new IllegalArgumentException("Positive history capacity required");
		this.usable = Objects.requireNonNull(usable);
		this.clock = Objects.requireNonNull(clock);
		this.capacity = capacity;
	}

	public synchronized Id nextId() { return new Id(incarnation, ++issued); }

	public synchronized Receipt start(Id id, String intent, long deadline, Supplier<Outcome> execute, Runnable cancel) {
		return start(id, intent, deadline, execute, reason -> cancel.run());
	}

	public synchronized Receipt start(Id id, String intent, long deadline, Supplier<Outcome> execute, Consumer<String> cancel) {
		Objects.requireNonNull(intent);
		Objects.requireNonNull(execute);
		Objects.requireNonNull(cancel);
		if (id == null || !incarnation.equals(id.session()) || id.sequence() <= 0 || id.sequence() > issued)
			return new Receipt(id, intent, State.REJECTED, "foreign-session-or-unissued-id", deadline);
		Receipt previous = history.get(id.sequence());
		if (previous != null)
			return previous.intent().equals(intent) && previous.deadline() == deadline ? previous
				: new Receipt(id, intent, State.REJECTED, "id-reused-with-different-request", deadline);
		if (id.sequence() <= consumed)
			return new Receipt(id, intent, State.REJECTED, "expired-receipt-never-reexecute", deadline);
		consumed = id.sequence();
		if (closed || !usable.getAsBoolean())
			return remember(new Receipt(id, intent, State.REJECTED, "session-closed", deadline));
		if (deadline <= clock.getAsLong())
			return remember(new Receipt(id, intent, State.REJECTED, "deadline-expired", deadline));
		if (active != null && !active.state().terminal()) {
			Receipt cancelled = cancel(active.id(), "superseded");
			if (cancelled.state() == State.FAILED)
				return remember(new Receipt(id, intent, State.REJECTED, "previous-cancellation-failed", deadline));
		}
		active = remember(new Receipt(id, intent, State.ACCEPTED, "accepted", deadline));
		stop = cancel;
		try {
			Outcome result = execute.get();
			// A synchronous native callback may have closed this session during execution.
			if (!active.state().terminal())
				active = remember(new Receipt(id, intent, result.state(), result.reason(), deadline));
		} catch (RuntimeException failure) {
			cancel(id, "execution-failed");
			active = remember(new Receipt(id, intent, State.FAILED, failure.getClass().getSimpleName(), deadline));
			throw failure;
		}
		return active;
	}

	public synchronized Receipt cancel(Id id, String reason) {
		if (active == null || !active.id().equals(id))
			return id != null && incarnation.equals(id.session()) ? history.get(id.sequence()) : null;
		if (active.state().terminal()) return active;
		active = remember(new Receipt(id, active.intent(), State.CANCELLED, reason, active.deadline()));
		try { stop.accept(reason); }
		catch (RuntimeException failure) {
			closed = true; // no new work after an uncertain native cancellation
			active = remember(new Receipt(id, active.intent(), State.FAILED, "cancel-failed", active.deadline()));
			throw failure;
		}
		return active;
	}

	public synchronized void poll(boolean completed, boolean nativeRunning) {
		if (active == null || active.state().terminal()) return;
		if (closed || !usable.getAsBoolean()) { cancel(active.id(), "session-closed"); return; }
		if (clock.getAsLong() >= active.deadline()) { cancel(active.id(), "deadline-expired"); return; }
		if (completed)
			active = remember(new Receipt(active.id(), active.intent(), State.SUCCEEDED, "destination-reached", active.deadline()));
		else if (!nativeRunning)
			active = remember(new Receipt(active.id(), active.intent(), State.FAILED, "native-action-interrupted", active.deadline()));
	}

	public synchronized void close() {
		closed = true;
		if (active != null) cancel(active.id(), "session-closed");
	}

	/** Completion belongs to one exact action; late callbacks cannot finish its successor. */
	public synchronized Receipt finish(Id id, State state, String reason) {
		if (!state.terminal()) throw new IllegalArgumentException("Terminal outcome required");
		if (active == null || !active.id().equals(id)) return null;
		if (!active.state().terminal())
			active = remember(new Receipt(id, active.intent(), state, reason, active.deadline()));
		return active;
	}

	public synchronized Receipt current() { return active; }
	public synchronized int retainedReceipts() { return history.size(); }

	private Receipt remember(Receipt receipt) {
		history.put(receipt.id().sequence(), receipt);
		while (history.size() > capacity) history.remove(history.keySet().iterator().next());
		return receipt;
	}
}
