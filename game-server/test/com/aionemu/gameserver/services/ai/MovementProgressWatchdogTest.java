package com.aionemu.gameserver.services.ai;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MovementProgressWatchdogTest {
	@Test void castPauseDoesNotCountAsStall() {
		var watch = new MovementProgressWatchdog();
		watch.stalled(0, 0, 0, 0, true);
		watch.stalled(20_000, 0, 0, 0, false);
		assertFalse(watch.stalled(20_100, 0, 0, 0, true));
		assertEquals(0, watch.stalls());
	}
	@Test void realProgressResetsStallsButNewRequestsDoNot() {
		var watch = new MovementProgressWatchdog();
		watch.stalled(0, 0, 0, 0, true);
		assertTrue(watch.stalled(8_000, 0, 0, 0, true));
		watch.stalled(8_001, 0, 0, 0, false);
		assertTrue(watch.stalled(16_001, 0, 0, 0, true));
		assertFalse(watch.stalled(16_002, 1, 0, 0, true));
		assertEquals(0, watch.stalls());
	}
}
