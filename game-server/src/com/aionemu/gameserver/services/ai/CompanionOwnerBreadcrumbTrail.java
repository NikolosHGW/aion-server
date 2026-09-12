package com.aionemu.gameserver.services.ai;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.List;

import com.aionemu.gameserver.utils.PositionUtil;

final class CompanionOwnerBreadcrumbTrail {

	static final int MAX_ENTRIES = 48;
	static final long MAX_AGE_MS = 30_000;
	static final float MAX_PATH_LENGTH = 120;
	static final float SAMPLE_DISTANCE = 2;
	static final long TURN_SAMPLE_INTERVAL_MS = 500;
	static final float TURN_SAMPLE_MIN_DISTANCE = 0.75f;
	static final float TURN_SAMPLE_MIN_ANGLE = 30;
	static final long MAX_SAMPLE_INTERVAL_MS = 2_000;
	static final float MAX_SAMPLE_DISTANCE = 8;
	static final float MIN_TIMED_XY_ALLOWANCE = 2;
	static final float MOVEMENT_TIMING_SLACK = 2;
	static final float POSITION_TOLERANCE = 1;
	static final float BASE_Z_TOLERANCE = 2;
	static final float MAX_GROUND_SLOPE = 2.5f;
	static final float MAX_TIMED_Z_TOLERANCE = 1;

	private final Deque<Breadcrumb> breadcrumbs = new ArrayDeque<>();
	private float pathLength;
	private long consumedCount;
	private long shortcutSkippedCount;
	private String discontinuity = "NONE";
	private boolean anchored;
	private Breadcrumb lastObservation;
	private Breadcrumb lastSample;

	void sample(int mapId, int instanceId, float x, float y, float z, byte heading, long now) {
		sample(mapId, instanceId, x, y, z, heading, MAX_SAMPLE_DISTANCE, now);
	}

	void sample(int mapId, int instanceId, float x, float y, float z, byte heading, float ownerGroundSpeed, long now) {
		purgeExpired(now);
		if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
			reset("NON_FINITE");
			return;
		}
		Breadcrumb observation = new Breadcrumb(mapId, instanceId, x, y, z, heading, now);
		if (lastObservation == null) {
			addSample(observation, 0);
			lastObservation = observation;
			return;
		}
		if (lastObservation.mapId() != mapId || lastObservation.instanceId() != instanceId) {
			reset(lastObservation.mapId() != mapId ? "MAP" : "INSTANCE");
			addSample(observation, 0);
			lastObservation = observation;
			return;
		}
		long elapsed = now - lastObservation.recordedAt();
		float xyDistance = horizontalDistance(lastObservation.x(), lastObservation.y(), x, y);
		if (elapsed < 0 || elapsed > MAX_SAMPLE_INTERVAL_MS) {
			breakContinuity("TIME_JUMP", mapId, instanceId, x, y, z, heading, now);
			return;
		}
		float plausibleDistance = plausibleDistance(ownerGroundSpeed, elapsed);
		float timedXyAllowance = Math.max(MIN_TIMED_XY_ALLOWANCE,
			plausibleDistance * MOVEMENT_TIMING_SLACK + POSITION_TOLERANCE);
		if (xyDistance > MAX_SAMPLE_DISTANCE || xyDistance > timedXyAllowance) {
			breakContinuity("XY_JUMP", mapId, instanceId, x, y, z, heading, now);
			return;
		}
		float zAllowance = BASE_Z_TOLERANCE + MAX_GROUND_SLOPE * xyDistance
			+ Math.min(MAX_TIMED_Z_TOLERANCE, plausibleDistance * 0.25f);
		if (Math.abs(z - lastObservation.z()) > zAllowance) {
			breakContinuity("Z_JUMP", mapId, instanceId, x, y, z, heading, now);
			return;
		}
		long sampleElapsed = now - lastSample.recordedAt();
		float sampleDistance = distance(lastSample.x(), lastSample.y(), lastSample.z(), x, y, z);
		boolean distanceSample = sampleDistance >= SAMPLE_DISTANCE;
		boolean turnSample = sampleElapsed >= TURN_SAMPLE_INTERVAL_MS && sampleDistance >= TURN_SAMPLE_MIN_DISTANCE
			&& headingDifference(lastSample.heading(), heading) >= TURN_SAMPLE_MIN_ANGLE;
		lastObservation = observation;
		if (!distanceSample && !turnSample)
			return;
		addSample(observation, sampleDistance);
		enforceBounds();
	}

	Breadcrumb next(float companionX, float companionY, float companionZ, float reachedDistance, long now) {
		purgeExpired(now);
		if (!anchored)
			return null;
		while (!breadcrumbs.isEmpty()) {
			Breadcrumb next = breadcrumbs.peekFirst();
			if (distance(companionX, companionY, companionZ, next.x(), next.y(), next.z()) > reachedDistance)
				return anchored ? next : null;
			removeFirst(true);
			anchored = true;
		}
		return null;
	}

	List<Breadcrumb> lookAhead(int limit) {
		if (!anchored || limit <= 0)
			return List.of();
		return breadcrumbs.stream().limit(limit).toList();
	}

	int skipBefore(Breadcrumb target) {
		if (!anchored || target == null || !breadcrumbs.contains(target))
			return 0;
		int skipped = 0;
		while (breadcrumbs.peekFirst() != target) {
			removeFirst(false);
			skipped++;
		}
		shortcutSkippedCount += skipped;
		return skipped;
	}

	void anchorAtCurrentOwner() {
		breadcrumbs.clear();
		pathLength = 0;
		if (lastObservation != null) {
			breadcrumbs.addLast(lastObservation);
			lastSample = lastObservation;
		}
		anchored = true;
	}

	void reset(String reason) {
		breadcrumbs.clear();
		pathLength = 0;
		consumedCount = 0;
		shortcutSkippedCount = 0;
		anchored = false;
		lastObservation = null;
		lastSample = null;
		discontinuity = reason == null || reason.isBlank() ? "NONE" : reason;
	}

	int size() {
		return breadcrumbs.size();
	}

	long consumedCount() {
		return consumedCount;
	}

	long shortcutSkippedCount() {
		return shortcutSkippedCount;
	}

	boolean isAnchored() {
		return anchored;
	}

	String discontinuity() {
		return discontinuity;
	}

	float pathLength() {
		return pathLength;
	}

	private void breakContinuity(String reason, int mapId, int instanceId, float x, float y, float z, byte heading, long now) {
		reset(reason);
		Breadcrumb observation = new Breadcrumb(mapId, instanceId, x, y, z, heading, now);
		addSample(observation, 0);
		lastObservation = observation;
	}

	private void addSample(Breadcrumb breadcrumb, float distanceFromPrevious) {
		breadcrumbs.addLast(breadcrumb);
		pathLength += distanceFromPrevious;
		lastSample = breadcrumb;
	}

	private void purgeExpired(long now) {
		if (!breadcrumbs.isEmpty() && now - breadcrumbs.peekFirst().recordedAt() > MAX_AGE_MS)
			reset("AGE");
	}

	private void enforceBounds() {
		if (breadcrumbs.size() <= MAX_ENTRIES && pathLength <= MAX_PATH_LENGTH)
			return;
		Breadcrumb current = breadcrumbs.peekLast();
		String reason = breadcrumbs.size() > MAX_ENTRIES ? "CAPACITY" : "PATH_LENGTH";
		reset(reason);
		if (current != null) {
			addSample(current, 0);
			lastObservation = current;
		}
	}

	private void removeFirst(boolean consumed) {
		Breadcrumb removed = breadcrumbs.removeFirst();
		Breadcrumb next = breadcrumbs.peekFirst();
		if (next != null)
			pathLength = Math.max(0, pathLength - distance(removed.x(), removed.y(), removed.z(), next.x(), next.y(), next.z()));
		else
			pathLength = 0;
		if (consumed)
			consumedCount++;
	}

	private static float distance(float x1, float y1, float z1, float x2, float y2, float z2) {
		return (float) PositionUtil.getDistance(x1, y1, z1, x2, y2, z2);
	}

	private static float horizontalDistance(float x1, float y1, float x2, float y2) {
		return (float) PositionUtil.getDistance(x1, y1, x2, y2);
	}

	private static float plausibleDistance(float ownerGroundSpeed, long elapsed) {
		if (!Float.isFinite(ownerGroundSpeed) || ownerGroundSpeed <= 0 || elapsed <= 0)
			return 0;
		return ownerGroundSpeed * elapsed / 1_000f;
	}

	private static float headingDifference(byte first, byte second) {
		float firstAngle = PositionUtil.convertHeadingToAngle(first);
		float secondAngle = PositionUtil.convertHeadingToAngle(second);
		float difference = Math.abs(firstAngle - secondAngle) % 360;
		return Math.min(difference, 360 - difference);
	}

	record Breadcrumb(int mapId, int instanceId, float x, float y, float z, byte heading, long recordedAt) {
	}
}
