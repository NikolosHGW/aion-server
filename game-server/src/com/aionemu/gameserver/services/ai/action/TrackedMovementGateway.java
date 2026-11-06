package com.aionemu.gameserver.services.ai.action;

import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

import com.aionemu.gameserver.services.ai.PlayerActionGateway;

/** Tracks the existing checked follow segments; does not implement pathfinding. */
public final class TrackedMovementGateway implements PlayerActionGateway {

	private final PlayerActionGateway delegate;
	private final ActionSession actions;
	private final LongSupplier clock;
	private final DoubleSupplier x, y, z;
	private final BooleanSupplier moving;
	private MovementResult destination;

	public TrackedMovementGateway(PlayerActionGateway delegate, BooleanSupplier usable, LongSupplier clock,
			DoubleSupplier x, DoubleSupplier y, DoubleSupplier z, BooleanSupplier moving) {
		this.delegate = delegate;
		this.actions = new ActionSession(usable, clock, 32);
		this.clock = clock;
		this.x = x; this.y = y; this.z = z; this.moving = moving;
	}

	@Override
	public MovementResult checkMovement(MovementIntent intent, float x, float y, float z) {
		poll();
		return delegate.checkMovement(intent, x, y, z);
	}

	@Override
	public MovementResult startMove(MovementIntent intent, MovementResult movement, float speed) {
		MovementResult[] result = { MovementResult.rejected(MovementPhase.START_EXECUTION,
			MovementRejectionReason.MOVE_CONTROLLER_REJECTED, Float.NaN, Float.NaN, Float.NaN) };
		try {
			actions.start(actions.nextId(), intent + ":" + movement + ":" + speed, clock.getAsLong() + 30_000, () -> {
				result[0] = delegate.startMove(intent, movement, speed);
				destination = result[0];
				if (!result[0].success()) delegate.stopMove();
				return new ActionSession.Outcome(result[0].success() ? ActionSession.State.RUNNING : ActionSession.State.REJECTED,
					result[0].reason().name());
			}, reason -> { if (!reason.equals("superseded")) delegate.stopMove(); });
		} catch (RuntimeException failure) {
			delegate.stopMove();
			throw failure;
		}
		return result[0];
	}

	public void poll() {
		if (destination == null || !destination.success()) return;
		double dx = x.getAsDouble() - destination.resolvedX(), dy = y.getAsDouble() - destination.resolvedY(),
			dz = z.getAsDouble() - destination.resolvedZ();
		actions.poll(dx * dx + dy * dy + dz * dz <= 0.25, moving.getAsBoolean());
	}

	@Override
	public void stopMove() {
		poll();
		var current = actions.current();
		if (current != null && !current.state().terminal()) actions.cancel(current.id(), "stop-requested");
		else delegate.stopMove();
	}
	@Override
	public void clearServerControlledMovementSpeed() { delegate.clearServerControlledMovementSpeed(); }
	public void close() { actions.close(); }
	public String status() { return "movementAction=" + actions.current(); }
}
