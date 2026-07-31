package com.aionemu.gameserver.services.ai;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.aionemu.gameserver.model.gameobjects.player.Player;

public final class ServerControlledPlayer implements ScheduledSyntheticPlayer {

	private final Player player;
	private final String templateDatabaseName;
	private final String runtimeName;
	private final SyntheticPlayerRole role;
	private final ServerPlayerController controller;
	private final BooleanSupplier featureEnabled;
	private final Consumer<String> removalRequest;
	private final AtomicReference<SyntheticPlayerState> state = new AtomicReference<>(SyntheticPlayerState.CREATED);

	public ServerControlledPlayer(Player player, String templateDatabaseName, String runtimeName, SyntheticPlayerRole role,
			ServerPlayerController controller, BooleanSupplier featureEnabled, Consumer<String> removalRequest) {
		this.player = player;
		this.templateDatabaseName = templateDatabaseName;
		this.runtimeName = runtimeName;
		this.role = role;
		this.controller = controller;
		this.featureEnabled = featureEnabled;
		this.removalRequest = removalRequest;
	}

	public Player getPlayer() {
		return player;
	}

	@Override
	public int getObjectId() {
		return player.getObjectId();
	}

	public String getTemplateDatabaseName() {
		return templateDatabaseName;
	}

	public String getRuntimeName() {
		return runtimeName;
	}

	public SyntheticPlayerRole getRole() {
		return role;
	}

	public int getCurrentWaypointIndex() {
		if (controller instanceof RouteServerPlayerController routeController)
			return routeController.getCurrentWaypointIndex();
		throw new IllegalStateException("Synthetic player does not use a route controller");
	}

	public RoutePoint getCurrentWaypoint() {
		if (controller instanceof RouteServerPlayerController routeController)
			return routeController.getCurrentWaypoint();
		throw new IllegalStateException("Synthetic player does not use a route controller");
	}

	public SyntheticPlayerState getState() {
		return state.get();
	}

	public void markRegistered() {
		if (!state.compareAndSet(SyntheticPlayerState.CREATED, SyntheticPlayerState.REGISTERED))
			throw new IllegalStateException("Cannot register synthetic player in state " + state.get());
	}

	public void activate() {
		if (!state.compareAndSet(SyntheticPlayerState.REGISTERED, SyntheticPlayerState.ACTIVE))
			throw new IllegalStateException("Cannot activate synthetic player in state " + state.get());
	}

	public boolean beginRemoval() {
		SyntheticPlayerState current;
		do {
			current = state.get();
			if (current == SyntheticPlayerState.REMOVING || current == SyntheticPlayerState.REMOVED)
				return false;
		} while (!state.compareAndSet(current, SyntheticPlayerState.REMOVING));
		return true;
	}

	public void markRemoved() {
		state.set(SyntheticPlayerState.REMOVED);
	}

	@Override
	public boolean isActive() {
		return state.get() == SyntheticPlayerState.ACTIVE;
	}

	@Override
	public boolean isFeatureEnabled() {
		return featureEnabled.getAsBoolean();
	}

	@Override
	public void tick() {
		controller.tick();
	}

	@Override
	public void requestRemoval(String reason) {
		removalRequest.accept(reason);
	}

	public void stopMovement() {
		controller.stop();
	}
}
