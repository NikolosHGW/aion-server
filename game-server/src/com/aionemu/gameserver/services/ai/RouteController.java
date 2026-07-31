package com.aionemu.gameserver.services.ai;

import java.util.ArrayList;
import java.util.List;

import com.aionemu.gameserver.utils.PositionUtil;

public final class RouteController {

	private final List<RoutePoint> points;
	private final float arrivalTolerance;
	private int currentWaypointIndex;

	public RouteController(List<RoutePoint> points, float arrivalTolerance) {
		if (points == null || points.size() < 3 || points.size() > 5)
			throw new IllegalArgumentException("A synthetic player route must contain 3 to 5 points");
		if (!Float.isFinite(arrivalTolerance) || arrivalTolerance <= 0)
			throw new IllegalArgumentException("Route arrival tolerance must be positive and finite");
		this.points = List.copyOf(points);
		this.arrivalTolerance = arrivalTolerance;
		this.currentWaypointIndex = 1;
	}

	public TickResult tick(RouteActor actor) {
		if (!actor.isEligible())
			return TickResult.INELIGIBLE;

		RoutePoint target = getCurrentWaypoint();
		if (actor.distanceTo(target) <= arrivalTolerance) {
			actor.stop();
			currentWaypointIndex = (currentWaypointIndex + 1) % points.size();
			target = getCurrentWaypoint();
		}

		if (!actor.isMoving()) {
			actor.start(target);
			return TickResult.STARTED;
		}
		return TickResult.MOVING;
	}

	public void stop(RouteActor actor) {
		actor.stop();
	}

	public int getCurrentWaypointIndex() {
		return currentWaypointIndex;
	}

	public RoutePoint getCurrentWaypoint() {
		return points.get(currentWaypointIndex);
	}

	public List<RoutePoint> getPoints() {
		return points;
	}

	public static List<Offset> parseOffsets(String value) {
		if (value == null || value.isBlank())
			throw new IllegalArgumentException("Route offsets are not configured");

		List<Offset> offsets = new ArrayList<>();
		for (String point : value.split(";")) {
			String[] coordinates = point.trim().split(",");
			if (coordinates.length != 2)
				throw new IllegalArgumentException("Invalid route offset: " + point);
			try {
				offsets.add(new Offset(Float.parseFloat(coordinates[0].trim()), Float.parseFloat(coordinates[1].trim())));
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Invalid route offset: " + point, e);
			}
		}
		if (offsets.size() < 3 || offsets.size() > 5)
			throw new IllegalArgumentException("Route offsets must contain 3 to 5 points");
		return List.copyOf(offsets);
	}

	public static void validateSegmentLengths(List<RoutePoint> points, float maxSegmentLength) {
		if (!Float.isFinite(maxSegmentLength) || maxSegmentLength <= 0)
			throw new IllegalArgumentException("Maximum route segment length must be positive and finite");
		for (int i = 0; i < points.size(); i++) {
			RoutePoint from = points.get(i);
			RoutePoint to = points.get((i + 1) % points.size());
			double distance = PositionUtil.getDistance(from.x(), from.y(), from.z(), to.x(), to.y(), to.z());
			if (distance <= 0 || distance > maxSegmentLength)
				throw new IllegalArgumentException("Route segment " + i + " has invalid length " + distance);
		}
	}

	public enum TickResult {
		INELIGIBLE,
		STARTED,
		MOVING
	}

	public interface RouteActor {

		boolean isEligible();

		boolean isMoving();

		double distanceTo(RoutePoint point);

		void start(RoutePoint point);

		void stop();
	}

	public record Offset(float x, float y) {

		public Offset {
			if (!Float.isFinite(x) || !Float.isFinite(y))
				throw new IllegalArgumentException("Route offsets must be finite");
		}
	}
}
