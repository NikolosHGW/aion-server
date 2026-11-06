package com.aionemu.gameserver.services.ai.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BodyCheckpointTest {
	@Test void failedProfileMutationSavesPreMutationStateAndSuccessfulOneUsesNewState() {
		var failed = new BodyCheckpoint<String>(true); failed.activate();
		failed.protectMutation(() -> "valid");
		failed.save(() -> "partially-mutated", value -> assertEquals("valid", value));
		var success = new BodyCheckpoint<String>(true); success.activate();
		success.protectMutation(() -> "old"); success.mutationSucceeded();
		success.save(() -> "new", value -> assertEquals("new", value));
	}
	@Test void failureRetainsExactSnapshotAndSuccessIsIdempotent() {
		var checkpoint = new BodyCheckpoint<String>(true);
		var freezes = new AtomicInteger();
		checkpoint.activate();
		assertThrows(IllegalStateException.class, () -> checkpoint.save(() -> "snapshot-" + freezes.incrementAndGet(), value -> {
			throw new IllegalStateException("db down");
		}));
		assertTrue(checkpoint.isPending());
		checkpoint.save(() -> "wrong-new-snapshot", value -> assertEquals("snapshot-1", value));
		checkpoint.save(() -> { fail("already saved"); return ""; }, value -> fail("duplicate write"));
		assertEquals(1, freezes.get()); assertTrue(checkpoint.isCommitted()); assertFalse(checkpoint.isPending());
	}
	@Test void templatesAndFailedSummonsNeverSave() {
		for (var checkpoint : java.util.List.of(new BodyCheckpoint<String>(false), new BodyCheckpoint<String>(true)))
			checkpoint.save(() -> { fail("not activated"); return ""; }, value -> fail());
		var legacy = new BodyCheckpoint<String>(false);
		legacy.activate(); legacy.save(() -> "template", value -> fail("template write"));
	}
	@Test void oneOwnerFailureDoesNotBlockOtherCheckpoint() {
		var first = new BodyCheckpoint<Integer>(true); var second = new BodyCheckpoint<Integer>(true);
		first.activate(); second.activate();
		assertThrows(IllegalStateException.class, () -> first.save(() -> 1, x -> { throw new IllegalStateException(); }));
		second.save(() -> 2, x -> assertEquals(2, x));
		assertTrue(first.isPending()); assertTrue(second.isCommitted());
	}
}
