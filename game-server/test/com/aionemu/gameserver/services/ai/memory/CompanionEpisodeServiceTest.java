package com.aionemu.gameserver.services.ai.memory;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import com.aionemu.gameserver.configs.main.AIConfig;
import com.aionemu.gameserver.questEngine.model.*;
import static com.aionemu.gameserver.model.DialogAction.SELECTED_QUEST_NOREWARD;

class CompanionEpisodeServiceTest {
	static final UUID TOKEN = UUID.fromString("17467d73-6fc2-4612-a87b-090c5d6e8c76");
	static QuestCompletionEvidence evidence(int owner) {
		return new QuestCompletionEvidence("test", owner, owner + 100, TOKEN, 1102, 1, 1234, 1234987, 1, 210010000, 1, 3);
	}
	static class Store implements CompanionMemoryStore {
		int saves, reconciles, reads;
		boolean fail, conflict;
		public boolean saveCandidate(QuestCompletionEvidence evidence) throws Exception {
			saves++; if (fail) throw new Exception("injected database failure"); return !conflict;
		}
		public int reconcile(String realm, long now, int limit) { reconciles++; assertEquals(8, limit); return 0; }
		public String status(String realm, int owner, int account) { reads++; return "memory=EMPTY"; }
	}
	@Test void onlyNarrowNativeCompletionIsEligible() {
		for (QuestStatus status : QuestStatus.values()) {
			var state = new QuestState(1102, status);
			assertEquals(status == QuestStatus.COMPLETE,
				CompanionEpisodeService.sourceEligible(1102, 0, state, 203057, SELECTED_QUEST_NOREWARD));
		}
		var complete = new QuestState(1102, QuestStatus.COMPLETE);
		assertFalse(CompanionEpisodeService.sourceEligible(1102, 1, complete, 203057, SELECTED_QUEST_NOREWARD));
		assertFalse(CompanionEpisodeService.sourceEligible(1102, 0, complete, 0, SELECTED_QUEST_NOREWARD));
		assertFalse(CompanionEpisodeService.sourceEligible(1102, 0, complete, 203057, 0));
		assertFalse(CompanionEpisodeService.sourceEligible(1120, 0, complete, 203057, SELECTED_QUEST_NOREWARD));
		complete.setCompleteCount(2);
		assertFalse(CompanionEpisodeService.sourceEligible(1102, 0, complete, 203057, SELECTED_QUEST_NOREWARD));
	}
	@Test void evidencePinsIdentityAndNativeOccurrenceRatherThanObservationTime() {
		var a = evidence(1);
		assertEquals(1234, QuestCompletionEvidence.second(new Timestamp(1234987)));
		assertTrue(a.sameOccurrence(new QuestCompletionEvidence("test", 1, 101, TOKEN, 1102, 1, 1234, 9999999, 2, 210010000, 1, 10)));
		assertFalse(a.sameOccurrence(evidence(2)));
		assertFalse(a.sameOccurrence(new QuestCompletionEvidence("test", 1, 101, UUID.randomUUID(), 1102, 1, 1234, 1234987, 1, 210010000, 1, 3)));
		assertFalse(a.sameOccurrence(new QuestCompletionEvidence("test", 1, 101, TOKEN, 1102, 1, 1235, 1235987, 1, 210010000, 1, 3)));
		for (double distance : new double[]{-1, 10.01, Double.NaN, Double.POSITIVE_INFINITY})
			assertThrows(IllegalArgumentException.class, () -> new QuestCompletionEvidence("test", 1, 101, TOKEN, 1102, 1, 1234, 1234987, 1, 210010000, 1, distance));
		assertFalse(QuestCompletionEvidence.validRealm("")); assertFalse(QuestCompletionEvidence.validRealm("a/b"));
	}
	@Test void boundedNonblockingQueueAndBatch() {
		var store = new Store();
		try (var service = new CompanionEpisodeService(store, () -> true, "test", () -> 1235000, 9)) {
			for (int i = 1; i <= 9; i++) assertTrue(service.offer(evidence(i)));
			assertFalse(service.offer(evidence(10)));
			service.runOnce(); assertEquals(8, store.saves);
			service.runOnce(); assertEquals(9, store.saves);
			assertTrue(service.diagnostics().contains("memoryDropped=1"));
		}
	}
	@Test void retriesAreBoundedAndRespectBackoff() {
		var store = new Store(); store.fail = true;
		var clock = new AtomicLong(1235000);
		try (var service = new CompanionEpisodeService(store, () -> true, "test", clock::get, 2)) {
			service.offer(evidence(1)); service.runOnce(); service.runOnce(); assertEquals(1, store.saves);
			clock.addAndGet(30000); service.runOnce(); assertEquals(2, store.saves);
			clock.addAndGet(30000); service.runOnce(); assertEquals(3, store.saves);
			clock.addAndGet(30000); service.runOnce(); assertEquals(3, store.saves);
			assertTrue(service.diagnostics().contains("memoryDropped=1"));
			assertTrue(service.diagnostics().contains("memoryQueue=0"));
		}
	}
	@Test void statusIsReadOnlyAndDisabledRequiresNoDatabase() {
		var store = new Store(); var enabled = new AtomicBoolean(false);
		try (var service = new CompanionEpisodeService(store, enabled::get, "test", () -> 1235000, 1)) {
			assertFalse(service.offer(evidence(1))); service.runOnce();
			assertEquals("memory=DISABLED", service.status(1, 1)); assertEquals(0, store.reads + store.saves + store.reconciles);
			enabled.set(true);
			assertEquals("memory=EMPTY", service.status(1, 1)); assertEquals(1, store.reads); assertEquals(0, store.saves + store.reconciles);
			store.conflict = true; service.offer(evidence(1)); service.runOnce();
			assertTrue(service.diagnostics().contains("memoryConflicts=1"));
		}
	}
	@Test void realmMismatchAndMissingRealmFailClosed() {
		var store = new Store();
		for (String realm : List.of("other", "")) try (var service = new CompanionEpisodeService(store, () -> true, realm, () -> 1235000, 1)) {
			assertFalse(service.offer(evidence(1)));
			if (realm.isEmpty()) { service.runOnce(); assertEquals("memory=UNCONFIGURED", service.status(1, 1)); }
		}
		assertEquals(0, store.saves + store.reads + store.reconciles);
	}
	@Test void shutdownWaitsForCurrentCommitAndRejectsFurtherWork() throws Exception {
		var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
		var store = new Store() {
			@Override public boolean saveCandidate(QuestCompletionEvidence evidence) throws Exception {
				entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return super.saveCandidate(evidence);
			}
		};
		var service = new CompanionEpisodeService(store, () -> true, "test", () -> 1235000, 2);
		try (var threads = Executors.newFixedThreadPool(2)) {
			service.offer(evidence(1)); service.offer(evidence(2));
			var worker = threads.submit(service::runOnce); assertTrue(entered.await(5, TimeUnit.SECONDS));
			var closing = threads.submit(service::close);
			try { assertThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS)); }
			finally { release.countDown(); }
			worker.get(5, TimeUnit.SECONDS); closing.get(5, TimeUnit.SECONDS);
			assertFalse(service.offer(evidence(3))); service.runOnce();
			assertEquals(1, store.saves); assertEquals(0, store.reconciles);
			assertTrue(service.diagnostics().contains("memoryQueue=0"));
		} finally { release.countDown(); service.close(); }
	}
	@Test void bothRollbackFlagsAndExistingProductFlagsAreRequired() {
		boolean[] before = { AIConfig.ENABLED, AIConfig.SYNTHETIC_PLAYERS_ENABLED, AIConfig.COMPANIONS_ENABLED,
			AIConfig.COMPANION_CREATION_ENABLED, AIConfig.COMPANION_PROFILE_ENABLED, AIConfig.WORLD_EVENTS_ENABLED, AIConfig.WORLD_MEMORY_ENABLED };
		try {
			AIConfig.ENABLED = AIConfig.SYNTHETIC_PLAYERS_ENABLED = AIConfig.COMPANIONS_ENABLED = true;
			AIConfig.COMPANION_CREATION_ENABLED = AIConfig.COMPANION_PROFILE_ENABLED = true;
			for (boolean events : new boolean[]{false,true}) for (boolean memory : new boolean[]{false,true}) {
				AIConfig.WORLD_EVENTS_ENABLED = events; AIConfig.WORLD_MEMORY_ENABLED = memory;
				assertEquals(events && memory, CompanionEpisodeService.flagsEnabled());
			}
			AIConfig.COMPANION_PROFILE_ENABLED = false; assertFalse(CompanionEpisodeService.flagsEnabled());
		} finally {
			AIConfig.ENABLED=before[0]; AIConfig.SYNTHETIC_PLAYERS_ENABLED=before[1]; AIConfig.COMPANIONS_ENABLED=before[2];
			AIConfig.COMPANION_CREATION_ENABLED=before[3]; AIConfig.COMPANION_PROFILE_ENABLED=before[4];
			AIConfig.WORLD_EVENTS_ENABLED=before[5]; AIConfig.WORLD_MEMORY_ENABLED=before[6];
		}
	}
}
