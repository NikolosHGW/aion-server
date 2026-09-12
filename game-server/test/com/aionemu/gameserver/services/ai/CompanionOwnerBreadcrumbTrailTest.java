package com.aionemu.gameserver.services.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class CompanionOwnerBreadcrumbTrailTest {

	@Test
	void samplesInitialDistanceAndTurnButNotStationaryDuplicates() {
		CompanionOwnerBreadcrumbTrail trail = new CompanionOwnerBreadcrumbTrail();
		trail.sample(1, 1, 0, 0, 0, (byte) 0, 0);
		trail.sample(1, 1, 0, 0, 0, (byte) 0, 600);
		trail.sample(1, 1, 2, 0, 0, (byte) 0, 800);
		trail.sample(1, 1, 3, 0, 0, (byte) 40, 1_400);

		assertEquals(3, trail.size());
	}

	@Test
	void stationaryObservationsDoNotCreateDuplicatesOrFalseTimeJump() {
		CompanionOwnerBreadcrumbTrail trail = anchoredTrail();
		trail.sample(1, 1, 0, 0, 0, (byte) 0, 1_000);
		trail.sample(1, 1, 0, 0, 0, (byte) 0, 2_000);
		trail.sample(1, 1, 2, 0, 0, (byte) 0, 2_200);

		assertEquals(2, trail.size());
		assertNotEquals("TIME_JUMP", trail.discontinuity());
	}

	@Test
	void gradualDownhillSamplesKeepContinuity() {
		CompanionOwnerBreadcrumbTrail trail = anchoredTrailAt(0, 0, 20);
		trail.sample(1, 1, 1.5f, 0, 17, (byte) 0, 7.8f, 200);
		trail.sample(1, 1, 3, 0, 14, (byte) 0, 7.8f, 400);

		assertEquals("NONE", trail.discontinuity());
		assertTrue(trail.size() >= 2);
	}

	@Test
	void gradualUphillSamplesKeepContinuity() {
		CompanionOwnerBreadcrumbTrail trail = anchoredTrailAt(0, 0, 10);
		trail.sample(1, 1, 1.5f, 0, 13, (byte) 0, 7.8f, 200);
		trail.sample(1, 1, 3, 0, 16, (byte) 0, 7.8f, 400);

		assertEquals("NONE", trail.discontinuity());
		assertTrue(trail.size() >= 2);
	}

	@Test
	void steepButPlausibleShortGroundSampleKeepsContinuity() {
		CompanionOwnerBreadcrumbTrail trail = anchoredTrail();

		trail.sample(1, 1, 1, 0, 4.7f, (byte) 0, 7.8f, 200);

		assertEquals("NONE", trail.discontinuity());
	}

	@Test
	void largeZJumpAtSmallHorizontalDistanceBreaksContinuity() {
		CompanionOwnerBreadcrumbTrail trail = anchoredTrail();

		trail.sample(1, 1, 0.25f, 0, 4, (byte) 0, 7.8f, 200);

		assertEquals("Z_JUMP", trail.discontinuity());
		assertNull(trail.next(0, 0, 0, 1, 200));
	}

	@Test
	void impossibleXyZAndTimeJumpsBreakContinuity() {
		CompanionOwnerBreadcrumbTrail xyTrail = anchoredTrail();
		xyTrail.sample(1, 1, 7, 0, 0, (byte) 0, 6, 100);
		assertEquals("XY_JUMP", xyTrail.discontinuity());

		CompanionOwnerBreadcrumbTrail zTrail = anchoredTrail();
		zTrail.sample(1, 1, 0.1f, 0, 10, (byte) 0, 6, 200);
		assertEquals("Z_JUMP", zTrail.discontinuity());

		CompanionOwnerBreadcrumbTrail timeTrail = anchoredTrail();
		timeTrail.sample(1, 1, 1, 0, 0, (byte) 0, 6, CompanionOwnerBreadcrumbTrail.MAX_SAMPLE_INTERVAL_MS + 1);
		assertEquals("TIME_JUMP", timeTrail.discontinuity());
	}

	@Test
	void legitimateHillTraversalRemainsAnchoredAndUsable() {
		CompanionOwnerBreadcrumbTrail trail = anchoredTrailAt(0, 0, 20);
		trail.sample(1, 1, 1.5f, 0, 16.5f, (byte) 0, 7.8f, 200);
		trail.sample(1, 1, 3, 0, 13, (byte) 0, 7.8f, 400);
		trail.sample(1, 1, 4.5f, 0, 9.5f, (byte) 0, 7.8f, 600);

		CompanionOwnerBreadcrumbTrail.Breadcrumb next = trail.next(0, 0, 20, 1, 600);

		assertNotNull(next);
		assertEquals(1.5f, next.x());
		assertEquals("NONE", trail.discontinuity());
	}

	@Test
	void lookAheadIsBoundedAndCommitsOnlyTheChosenShortcut() {
		CompanionOwnerBreadcrumbTrail trail = anchoredTrail();
		trail.sample(1, 1, 2, 0, 0, (byte) 0, 200);
		trail.sample(1, 1, 4, 0, 0, (byte) 0, 400);
		trail.sample(1, 1, 6, 0, 0, (byte) 0, 600);
		trail.sample(1, 1, 8, 0, 0, (byte) 0, 800);
		trail.next(0, 0, 0, 1, 800);

		var candidates = trail.lookAhead(3);
		assertEquals(List.of(2f, 4f, 6f), candidates.stream().map(CompanionOwnerBreadcrumbTrail.Breadcrumb::x).toList());
		assertEquals(2, trail.skipBefore(candidates.get(2)));
		assertEquals(2, trail.shortcutSkippedCount());
		assertEquals(6, trail.next(0, 0, 0, 1, 800).x());
	}

	@Test
	void consumesReachedPointsChronologically() {
		CompanionOwnerBreadcrumbTrail trail = anchoredTrail();
		trail.sample(1, 1, 2, 0, 0, (byte) 0, 200);
		trail.sample(1, 1, 4, 0, 0, (byte) 0, 400);

		CompanionOwnerBreadcrumbTrail.Breadcrumb next = trail.next(0, 0, 0, 1, 400);

		assertNotNull(next);
		assertEquals(2, next.x());
		assertEquals(1, trail.consumedCount());
	}

	@Test
	void capacityBoundBreaksContinuityFailClosed() {
		CompanionOwnerBreadcrumbTrail trail = anchoredTrail();
		for (int i = 1; i <= CompanionOwnerBreadcrumbTrail.MAX_ENTRIES; i++)
			trail.sample(1, 1, i * 2, 0, 0, (byte) 0, i * 100L);

		assertEquals("CAPACITY", trail.discontinuity());
		assertNull(trail.next(0, 0, 0, 1, 5_000));
	}

	@Test
	void pathLengthBoundBreaksContinuityFailClosed() {
		CompanionOwnerBreadcrumbTrail trail = anchoredTrail();
		for (int i = 1; i <= 41; i++)
			trail.sample(1, 1, i * 3, 0, 0, (byte) 0, i * 500L);

		assertEquals("PATH_LENGTH", trail.discontinuity());
		assertNull(trail.next(0, 0, 0, 1, 5_000));
	}

	@Test
	void ageAndSpatialJumpsBreakContinuity() {
		CompanionOwnerBreadcrumbTrail trail = anchoredTrail();
		trail.sample(1, 1, 1, 0, 0, (byte) 0, CompanionOwnerBreadcrumbTrail.MAX_AGE_MS + 1);
		assertEquals("AGE", trail.discontinuity());
		assertNull(trail.next(0, 0, 0, 1, CompanionOwnerBreadcrumbTrail.MAX_AGE_MS + 1));

		trail = anchoredTrail();
		trail.sample(1, 1, 9, 0, 0, (byte) 0, 200);
		assertEquals("XY_JUMP", trail.discontinuity());
		assertNull(trail.next(0, 0, 0, 1, 200));

		trail = anchoredTrail();
		trail.sample(1, 1, 1, 0, 5, (byte) 0, 200);
		assertEquals("Z_JUMP", trail.discontinuity());
		assertNull(trail.next(0, 0, 0, 1, 200));
	}

	@Test
	void mapInstanceAndExplicitResetClearTrail() {
		CompanionOwnerBreadcrumbTrail trail = anchoredTrail();
		trail.sample(2, 1, 1, 0, 0, (byte) 0, 200);
		assertEquals("MAP", trail.discontinuity());
		assertNull(trail.next(0, 0, 0, 1, 200));

		trail = anchoredTrail();
		trail.sample(1, 2, 1, 0, 0, (byte) 0, 200);
		assertEquals("INSTANCE", trail.discontinuity());
		assertNull(trail.next(0, 0, 0, 1, 200));

		trail.reset("STAY");
		assertEquals(0, trail.size());
		assertEquals(0, trail.consumedCount());
		assertEquals("STAY", trail.discontinuity());
	}

	private CompanionOwnerBreadcrumbTrail anchoredTrail() {
		return anchoredTrailAt(0, 0, 0);
	}

	private CompanionOwnerBreadcrumbTrail anchoredTrailAt(float x, float y, float z) {
		CompanionOwnerBreadcrumbTrail trail = new CompanionOwnerBreadcrumbTrail();
		trail.sample(1, 1, x, y, z, (byte) 0, 0);
		trail.anchorAtCurrentOwner();
		return trail;
	}
}
