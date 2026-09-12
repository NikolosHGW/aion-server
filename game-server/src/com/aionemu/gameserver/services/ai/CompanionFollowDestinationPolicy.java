package com.aionemu.gameserver.services.ai;

import java.util.List;

import com.aionemu.gameserver.services.ai.PlayerActionGateway.MovementRejectionReason;

final class CompanionFollowDestinationPolicy {

	private static final double REAR_DEGREES = 180;
	private static final double SIDE_OFFSET_DEGREES = 45;

	static List<Candidate> candidates(float ownerX, float ownerY, float ownerZ, float ownerAngleDegrees, float offsetDistance) {
		double rearAngle = ownerAngleDegrees + REAR_DEGREES;
		return List.of(
			candidate(CandidateKind.REAR, ownerX, ownerY, ownerZ, rearAngle, offsetDistance),
			candidate(CandidateKind.REAR_LEFT, ownerX, ownerY, ownerZ, rearAngle - SIDE_OFFSET_DEGREES, offsetDistance),
			candidate(CandidateKind.REAR_RIGHT, ownerX, ownerY, ownerZ, rearAngle + SIDE_OFFSET_DEGREES, offsetDistance));
	}

	static boolean allowsAlternateCandidate(MovementRejectionReason reason) {
		return switch (reason) {
			case DESTINATION_HAS_NO_GEODATA, DESTINATION_OUTSIDE_WORLD_REGION, COLLISION, LINE_OF_SIGHT -> true;
			default -> false;
		};
	}

	private static Candidate candidate(CandidateKind kind, float ownerX, float ownerY, float ownerZ, double angleDegrees, float distance) {
		double angleRadians = Math.toRadians(angleDegrees);
		return new Candidate(kind, ownerX + (float) Math.cos(angleRadians) * distance,
			ownerY + (float) Math.sin(angleRadians) * distance, ownerZ);
	}

	enum CandidateKind {
		REAR,
		REAR_LEFT,
		REAR_RIGHT
	}

	record Candidate(CandidateKind kind, float x, float y, float z) {
	}

	private CompanionFollowDestinationPolicy() {
	}
}
