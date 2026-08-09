package com.aionemu.gameserver.services.ai.quest;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.services.ai.quest.CompanionGoalSession.ChoiceResult;

class QuestGoalCommandFormatterTest {

	@Test
	void expectedChooseOutcomesAreFormattedWithoutExceptions() {
		assertTrue(QuestGoalCommandFormatter.formatChoice(ChoiceResult.chosen(CompanionGoalSessionTest.plan(1102, 3)))
			.contains("runtime AI goal only; quest not accepted"));
		assertEquals("Companion goal choose: NO_OFFER; reason=NO_OFFER",
			assertDoesNotThrow(() -> QuestGoalCommandFormatter.formatChoice(ChoiceResult.noOffer())));
		assertEquals("Companion goal choose: STALE_OFFER; reason=INELIGIBLE_ALREADY_ACTIVE",
			assertDoesNotThrow(() -> QuestGoalCommandFormatter.formatChoice(
				ChoiceResult.stale(1102, QuestGoalReason.INELIGIBLE_ALREADY_ACTIVE))));
		assertEquals("Companion goal choose: INELIGIBLE; reason=FEATURE_DISABLED",
			assertDoesNotThrow(() -> QuestGoalCommandFormatter.formatChoice(ChoiceResult.ineligible(QuestGoalReason.FEATURE_DISABLED))));
		assertEquals("Companion goal choose: SESSION_MISMATCH; reason=SESSION_MISMATCH",
			assertDoesNotThrow(() -> QuestGoalCommandFormatter.formatChoice(ChoiceResult.sessionMismatch())));
		assertTrue(QuestGoalCommandFormatter.formatChoice(ChoiceResult.alreadyComplete(1102)).contains("ALREADY_COMPLETE"));
	}

	@Test
	void completedNonRepeatableQuestIsNotFormattedAsANewOffer() {
		String message = QuestGoalCommandFormatter.formatProposal(QuestGoalProposalResult.alreadyComplete(1102));

		assertTrue(message.contains("ALREADY_COMPLETE"));
		assertTrue(message.contains("will not be offered again"));
		assertFalse(message.contains("Step 1"));
	}
}
