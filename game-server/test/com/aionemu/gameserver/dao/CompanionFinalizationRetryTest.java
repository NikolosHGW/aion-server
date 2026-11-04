package com.aionemu.gameserver.dao;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class CompanionFinalizationRetryTest {

	@Test
	void rolledBackDeadlockRetriesTheTransactionAndReturnsItsResult() throws Exception {
		var attempts = new AtomicInteger();
		Object binding = new Object();
		Object actual = CompanionPersistenceDAO.retryRolledBackDeadlock(() -> {
			if (attempts.incrementAndGet() == 1)
				throw new SQLException("deadlock", "40001", 1213);
			return binding;
		});
		assertSame(binding, actual);
		assertEquals(2, attempts.get());
	}

	@Test
	void retriesAreBounded() {
		var attempts = new AtomicInteger();
		assertThrows(SQLException.class, () -> CompanionPersistenceDAO.retryRolledBackDeadlock(() -> {
			attempts.incrementAndGet();
			throw new SQLException("deadlock", "40001", 1213);
		}));
		assertEquals(3, attempts.get());
	}

	@Test
	void connectionLossConstraintErrorAndFailedRollbackAreNotRetried() {
		SQLException rollbackFailed = new SQLException("deadlock", "40001", 1213);
		rollbackFailed.addSuppressed(new SQLException("rollback failed"));
		for (var failure : new SQLException[] { new SQLException("connection lost", "08006"),
			new SQLException("duplicate", "23000", 1062), rollbackFailed }) {
			var attempts = new AtomicInteger();
			assertSame(failure, assertThrows(SQLException.class, () -> CompanionPersistenceDAO.retryRolledBackDeadlock(() -> {
				attempts.incrementAndGet();
				throw failure;
			})));
			assertEquals(1, attempts.get());
		}
	}
}
