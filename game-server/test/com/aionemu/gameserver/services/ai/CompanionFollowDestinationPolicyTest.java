package com.aionemu.gameserver.services.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.services.ai.CompanionFollowDestinationPolicy.Candidate;
import com.aionemu.gameserver.services.ai.CompanionFollowDestinationPolicy.CandidateKind;
import com.aionemu.gameserver.services.ai.PlayerActionGateway.MovementRejectionReason;

class CompanionFollowDestinationPolicyTest {

	@Test
	void candidatesAreBoundedAndDeterministic() {
		List<Candidate> candidates = CompanionFollowDestinationPolicy.candidates(10, 20, 30, 0, 2);

		assertEquals(List.of(CandidateKind.REAR, CandidateKind.REAR_LEFT, CandidateKind.REAR_RIGHT),
			candidates.stream().map(Candidate::kind).toList());
		assertEquals(3, candidates.size());
		assertEquals(8, candidates.get(0).x(), 0.0001);
		assertEquals(20, candidates.get(0).y(), 0.0001);
		assertEquals(30, candidates.get(0).z(), 0.0001);
	}

	@Test
	void onlySpatialDestinationFailuresAllowAlternates() {
		for (MovementRejectionReason reason : List.of(MovementRejectionReason.DESTINATION_HAS_NO_GEODATA,
			MovementRejectionReason.DESTINATION_OUTSIDE_WORLD_REGION, MovementRejectionReason.COLLISION,
			MovementRejectionReason.LINE_OF_SIGHT))
			assertTrue(CompanionFollowDestinationPolicy.allowsAlternateCandidate(reason), reason.name());

		for (MovementRejectionReason reason : List.of(MovementRejectionReason.OWNER_DEAD, MovementRejectionReason.MOVEMENT_NOT_ALLOWED,
			MovementRejectionReason.OWNER_LINE_OF_SIGHT, MovementRejectionReason.COMPANION_NOT_IN_WORLD,
			MovementRejectionReason.VISIBILITY_TARGET_MAP_OR_INSTANCE_MISMATCH))
			assertFalse(CompanionFollowDestinationPolicy.allowsAlternateCandidate(reason), reason.name());
	}
}
