package com.aionemu.gameserver.model.stats.container;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.aionemu.gameserver.model.gameobjects.Creature;

class KillingBlowReservationTest {
	private final CreatureLifeStats<Creature> life = new CreatureLifeStats<>(null) {
		@Override public int getMaxHp() { return 100; }
		@Override public int getMaxMp() { return 100; }
	};
	@Test void cancelledPendingHitReleasesOnlyItsOwnMarkerWithoutChangingHp() {
		Object cast = new Object(), stranger = new Object();
		life.setKillingBlow(150, cast);
		life.releaseKillingBlowReservation(stranger);
		assertTrue(life.hasKillingBlowReservation(cast));
		life.releaseKillingBlowReservation(cast);
		assertFalse(life.isAboutToDie());
		assertEquals(100, life.getCurrentHp());
	}
	@Test void humanNativeHitCannotBeClearedByOldCompanionCallback() {
		Object cast = new Object();
		life.setKillingBlow(150, cast);
		life.setKillingBlow(170);
		life.releaseKillingBlowReservation(cast);
		assertTrue(life.isAboutToDie());
		assertFalse(life.hasKillingBlowReservation(cast));
	}
	@Test void companionCannotReplaceAnotherPendingHit() {
		Object first = new Object(), second = new Object();
		life.setKillingBlow(150, first);
		life.setKillingBlow(160, second);
		assertTrue(life.hasKillingBlowReservation(first));
		assertFalse(life.hasKillingBlowReservation(second));
		life.releaseKillingBlowReservation(second);
		assertTrue(life.isAboutToDie());
	}
}
