package com.aionemu.gameserver.services.ai;

import java.util.List;

public final class RoutePreflight {

	private RoutePreflight() {
	}

	public static void validate(List<RoutePoint> points, float maxSegmentLength, Geometry geometry) {
		if (points == null || points.size() < 3 || points.size() > 5)
			throw new IllegalArgumentException("A route must contain 3 to 5 points");
		RouteController.validateSegmentLengths(points, maxSegmentLength);
		for (int i = 0; i < points.size(); i++) {
			RoutePoint from = points.get(i);
			RoutePoint to = points.get((i + 1) % points.size());
			if (!geometry.isValidPoint(from))
				throw new IllegalArgumentException("Route point " + i + " is outside a valid world region");
			if (!geometry.isSegmentClear(from, to))
				throw new IllegalArgumentException("Route segment " + i + " is blocked");
			if (!geometry.hasLineOfSight(from, to))
				throw new IllegalArgumentException("Route segment " + i + " has no line of sight");
		}
	}

	public interface Geometry {

		boolean isValidPoint(RoutePoint point);

		boolean isSegmentClear(RoutePoint from, RoutePoint to);

		boolean hasLineOfSight(RoutePoint from, RoutePoint to);
	}
}
