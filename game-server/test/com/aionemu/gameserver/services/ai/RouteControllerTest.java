package com.aionemu.gameserver.services.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class RouteControllerTest {

	private static final List<RoutePoint> ROUTE = List.of(
		new RoutePoint(0, 0, 0),
		new RoutePoint(5, 0, 0),
		new RoutePoint(5, 5, 0),
		new RoutePoint(0, 5, 0));

	@Test
	void routeRequiresThreeToFivePoints() {
		assertThrows(IllegalArgumentException.class, () -> new RouteController(ROUTE.subList(0, 2), 0.25f));
		assertDoesNotThrow(() -> new RouteController(ROUTE.subList(0, 3), 0.25f));
		assertDoesNotThrow(() -> new RouteController(ROUTE, 0.25f));
		assertThrows(IllegalArgumentException.class,
			() -> new RouteController(List.of(ROUTE.get(0), ROUTE.get(1), ROUTE.get(2), ROUTE.get(3), new RoutePoint(-1, 5, 0), new RoutePoint(-1, 0, 0)),
				0.25f));
	}

	@Test
	void routeRejectsInvalidNaNAndLongSegment() {
		assertThrows(IllegalArgumentException.class, () -> new RoutePoint(Float.NaN, 0, 0));
		assertThrows(IllegalArgumentException.class, () -> RouteController.validateSegmentLengths(ROUTE, 4));
		assertDoesNotThrow(() -> RouteController.validateSegmentLengths(ROUTE, 5));
	}

	@Test
	void routeRejectsInvalidRegionNaNZAndBlockedSegment() {
		assertThrows(IllegalArgumentException.class, () -> new RoutePoint(0, 0, Float.NaN));
		assertThrows(IllegalArgumentException.class, () -> RoutePreflight.validate(ROUTE, 5, new TestGeometry(false, true, true)));
		assertThrows(IllegalArgumentException.class, () -> RoutePreflight.validate(ROUTE, 5, new TestGeometry(true, false, true)));
		assertThrows(IllegalArgumentException.class, () -> RoutePreflight.validate(ROUTE, 5, new TestGeometry(true, true, false)));
		assertDoesNotThrow(() -> RoutePreflight.validate(ROUTE, 5, new TestGeometry(true, true, true)));
	}

	@Test
	void routeOffsetsRequireThreeToFiveFinitePoints() {
		assertThrows(IllegalArgumentException.class, () -> RouteController.parseOffsets("0,0;1,0"));
		assertThrows(IllegalArgumentException.class, () -> RouteController.parseOffsets("0,0;NaN,0;1,1"));
		assertEquals(4, RouteController.parseOffsets("0,0;5,0;5,5;0,5").size());
	}

	@Test
	void routeLoopsAndStopsAtEachWaypoint() {
		RouteController controller = new RouteController(ROUTE, 0.25f);
		TestActor actor = new TestActor();

		assertEquals(RouteController.TickResult.STARTED, controller.tick(actor));
		assertEquals(ROUTE.get(1), actor.lastStarted);

		for (int i = 0; i < ROUTE.size(); i++) {
			actor.arriveAt(controller.getCurrentWaypoint());
			assertEquals(RouteController.TickResult.STARTED, controller.tick(actor));
		}

		assertEquals(ROUTE.size(), actor.stopCount);
		assertEquals(1, controller.getCurrentWaypointIndex());
		assertEquals(ROUTE.get(1), actor.lastStarted);
	}

	@Test
	void offlineElapsedTimeDoesNotAdvanceRoute() {
		RouteController controller = new RouteController(ROUTE, 0.25f);
		TestActor actor = new TestActor();
		actor.eligible = false;

		for (int i = 0; i < 100; i++)
			assertEquals(RouteController.TickResult.INELIGIBLE, controller.tick(actor));

		assertEquals(1, controller.getCurrentWaypointIndex());
		assertEquals(0, actor.startCount);
		assertEquals(0, actor.stopCount);
	}

	private static final class TestActor implements RouteController.RouteActor {

		private boolean eligible = true;
		private boolean moving;
		private float x;
		private float y;
		private float z;
		private int startCount;
		private int stopCount;
		private RoutePoint lastStarted;

		@Override
		public boolean isEligible() {
			return eligible;
		}

		@Override
		public boolean isMoving() {
			return moving;
		}

		@Override
		public double distanceTo(RoutePoint point) {
			double dx = point.x() - x;
			double dy = point.y() - y;
			double dz = point.z() - z;
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		}

		@Override
		public void start(RoutePoint point) {
			startCount++;
			lastStarted = point;
			moving = true;
		}

		@Override
		public void stop() {
			stopCount++;
			moving = false;
		}

		private void arriveAt(RoutePoint point) {
			x = point.x();
			y = point.y();
			z = point.z();
			moving = true;
		}
	}

	private record TestGeometry(boolean validPoint, boolean clearSegment, boolean lineOfSight) implements RoutePreflight.Geometry {

		@Override
		public boolean isValidPoint(RoutePoint point) {
			return validPoint;
		}

		@Override
		public boolean isSegmentClear(RoutePoint from, RoutePoint to) {
			return clearSegment;
		}

		@Override
		public boolean hasLineOfSight(RoutePoint from, RoutePoint to) {
			return lineOfSight;
		}
	}
}
