package com.aionemu.gameserver.services.ai;

import com.aionemu.gameserver.model.gameobjects.player.Player;

public final class SyntheticPlayerRuntime {

	private final ServerControlledPlayerRegistry registry = new ServerControlledPlayerRegistry();
	private final SyntheticPlayerScheduler scheduler = new SyntheticPlayerScheduler();

	private SyntheticPlayerRuntime() {
	}

	public static SyntheticPlayerRuntime getInstance() {
		return SingletonHolder.INSTANCE;
	}

	public ServerControlledPlayerRegistry getRegistry() {
		return registry;
	}

	public SyntheticPlayerScheduler getScheduler() {
		return scheduler;
	}

	public boolean isServerControlled(Player player) {
		return registry.contains(player);
	}

	public void shutdownScheduler() {
		scheduler.shutdown();
	}

	private static final class SingletonHolder {

		private static final SyntheticPlayerRuntime INSTANCE = new SyntheticPlayerRuntime();
	}
}
