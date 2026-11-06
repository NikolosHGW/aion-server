package com.aionemu.gameserver.services.ai.combat;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.aionemu.gameserver.model.PlayerClass;

class CompanionSkillPolicyTest {
	@Test void approachIsBoundedAndCannotBecomeAnUnattendedPursuit() {
		var step = CompanionSkillPolicy.approach(0, 0, 0, 7, 0, 0, 1, 0, 0);
		assertEquals(3f, step.x());
		assertNull(CompanionSkillPolicy.approach(0, 0, 0, 12, 0, 0, 1, 0, 0));
		assertNull(CompanionSkillPolicy.approach(20, 0, 0, 7, 0, 0, 1, 0, 0));
		assertNull(CompanionSkillPolicy.approach(Float.NaN, 0, 0, 7, 0, 0, 1, 0, 0));
		assertNull(CompanionSkillPolicy.approach(0, 0, 0, 0.5, 0, 0, 1, 0, 0));
	}
	@Test void ownerHealingHasAnExplicitThresholdAndNoResurrection() {
		assertTrue(CompanionSkillPolicy.shouldHeal(PlayerClass.CLERIC, 70, 100));
		assertFalse(CompanionSkillPolicy.shouldHeal(PlayerClass.CLERIC, 71, 100));
		assertFalse(CompanionSkillPolicy.shouldHeal(PlayerClass.CLERIC, 0, 100));
		assertFalse(CompanionSkillPolicy.shouldHeal(PlayerClass.CLERIC, 1, 0));
		assertFalse(CompanionSkillPolicy.shouldHeal(PlayerClass.GLADIATOR, 10, 100));
	}
	@Test void unsupportedRolesHaveNoImplicitGenericRotation() {
		assertTrue(CompanionSkillPolicy.attacks(PlayerClass.SORCERER).isEmpty());
		assertEquals("WA_ROBUSTBLOW", CompanionSkillPolicy.attacks(PlayerClass.GLADIATOR).getFirst());
		assertFalse(CompanionSkillPolicy.supported(null, "CL_HEAL"));
	}
}
