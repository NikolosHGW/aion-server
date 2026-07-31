package com.aionemu.gameserver.services.ai;

final class SpawnTransaction {

	private SpawnTransaction() {
	}

	static void execute(Steps steps) {
		try {
			steps.registerWrapper();
			steps.storeWorldObject();
			steps.spawnWorldObject();
			steps.registerScheduler();
		} catch (RuntimeException | Error failure) {
			try {
				steps.rollback();
			} catch (RuntimeException | Error cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			try {
				steps.verifyRolledBack();
			} catch (RuntimeException | Error verificationFailure) {
				failure.addSuppressed(verificationFailure);
			}
			throw failure;
		}
	}

	interface Steps {

		void registerWrapper();

		void storeWorldObject();

		void spawnWorldObject();

		void registerScheduler();

		void rollback();

		void verifyRolledBack();
	}
}
