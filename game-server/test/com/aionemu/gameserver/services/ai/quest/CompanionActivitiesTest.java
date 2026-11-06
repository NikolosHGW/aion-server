package com.aionemu.gameserver.services.ai.quest;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import com.aionemu.gameserver.model.templates.spawns.*;
import com.aionemu.gameserver.questEngine.model.QuestStatus;

class CompanionActivitiesTest {
	@Test void allowlistRejectsUnknownDuplicatesAndMalformedSuffix() {
		assertEquals(Set.of(1102, 1120), QuestExecutionAllowlist.parse("1120,1102"));
		for (String value : List.of("1120,1120", "1102,", "1102,1112", "1120,2102", "*"))
			assertFalse(QuestExecutionAllowlist.isValid(value), value);
		assertTrue(QuestExecutionAllowlist.allows("1120", 1120));
		assertFalse(QuestExecutionAllowlist.allows("1102", 1120));
	}
	@Test void newManifestRejectsWalkersWrongMapOldTargetsAndInventedCoordinates() {
		var manifest = QuestExecutionManifest.curated1120();
		assertEquals(11, manifest.approvedSpawnCount());
		var p = manifest.approvedTargetSpawns().getFirst();
		var group = new SpawnGroup(p.mapId(), p.npcId(), 0, null);
		assertTrue(manifest.matchesTarget(p.npcId(), new SpawnTemplate(group, p.x(), p.y(), p.z(), p.heading(), 0, null, 0)));
		assertFalse(manifest.matchesTarget(p.npcId(), new SpawnTemplate(group, p.x(), p.y(), p.z(), p.heading(), 8, null, 0)));
		assertFalse(manifest.matchesTarget(p.npcId(), new SpawnTemplate(group, p.x()+2, p.y(), p.z(), p.heading(), 0, null, 0)));
		assertFalse(manifest.matchesTarget(210133, new SpawnTemplate(group, p.x(), p.y(), p.z(), p.heading(), 0, null, 0)));
		assertThrows(IllegalArgumentException.class, () -> QuestExecutionManifest.curated(1112));
	}
	@Test void guideReadsAcceptanceProgressTurnInAndCompletion() {
		var plan = plan();
		assertTrue(CompanionActivityGuide.next(plan, QuestNativeSnapshot.absent()).contains("примите"));
		assertTrue(CompanionActivityGuide.next(plan, state(QuestStatus.START, 3)).contains("3/9"));
		assertTrue(CompanionActivityGuide.next(plan, state(QuestStatus.START, 9)).contains("Сдайте"));
		assertTrue(CompanionActivityGuide.next(plan, state(QuestStatus.REWARD, 9)).contains("Сдайте"));
		assertTrue(CompanionActivityGuide.next(plan, state(QuestStatus.COMPLETE, 9)).contains("завершено"));
	}
	@Test void ownersUseIndependentNativeReadersAndRepeatedStatusDoesNotAdvanceProgress() {
		var nativeA = new AtomicReference<>(state(QuestStatus.START, 2));
		var nativeB = new AtomicReference<>(state(QuestStatus.START, 7));
		for (int i=0; i<20; i++) {
			assertTrue(CompanionActivityGuide.next(plan(), nativeA.get()).contains("2/9"));
			assertTrue(CompanionActivityGuide.next(plan(), nativeB.get()).contains("7/9"));
		}
		assertEquals(2, nativeA.get().var0()); assertEquals(7, nativeB.get().var0());
	}
	@Test void trackerSwitchesManifestAndSuppressesUnchangedNotifications() {
		var now = new AtomicLong(1000); var journal = new AtomicReference<>(state(QuestStatus.START, 4));
		var messages = new ArrayList<QuestExecutionTransition>();
		var runtime = new CompanionQuestExecutionRuntime(10, 20, 30);
		runtime.attach(new QuestExecutionPlan(plan(), QuestExecutionManifest.curated1120()), journal::get,
			() -> new QuestExecutionRuntimeContext(10,20,30,QuestExecutionRole.PERSONAL_COMPANION,210010000,1,210010000,1,true,true,true),
			messages::add, now::get, 500);
		assertEquals(1120, runtime.authorizationView().orElseThrow().session().questId());
		assertEquals(9, runtime.authorizationView().orElseThrow().snapshot().requiredProgress());
		now.addAndGet(500); runtime.pollIfDue(); assertEquals(1, messages.size());
		journal.set(state(QuestStatus.START, 9)); now.addAndGet(500); runtime.pollIfDue();
		assertEquals(QuestExecutionState.READY_TO_TURN_IN, runtime.authorizationView().orElseThrow().snapshot().executionState());
		String notification = QuestExecutionCommandFormatter.formatTransition(QuestExecutionManifest.curated1120(), messages.getLast());
		assertTrue(notification.contains("1120") && notification.contains("203082"));
		assertFalse(notification.contains("203057"));
		runtime.clear("rollback"); assertTrue(runtime.authorizationView().isEmpty());
	}
	private static QuestNativeSnapshot state(QuestStatus status, int progress) { return new QuestNativeSnapshot(true,status,progress,0); }
	private static QuestGoalPlan plan() {
		var start = new QuestGoalStep(QuestGoalStep.Type.TALK_TO_START_NPC, List.of(203082), List.of("NPC"), 1,
			new QuestGoalStep.ReferenceCoordinate(210010000,203082,0,429.762f,1756.56f,119.772f));
		var kill = new QuestGoalStep(QuestGoalStep.Type.KILL_NPC_SET, List.of(210142,210143), List.of("Worg1","Worg2"),9,
			new QuestGoalStep.ReferenceCoordinate(210010000,210142,0,467.97f,1669.97f,125.252f));
		var end = new QuestGoalStep(QuestGoalStep.Type.REPORT_TO_END_NPC,start.targetIds(),start.localizedTargetNames(),1,start.referenceCoordinate());
		return new QuestGoalPlan(1120,"Thinning out Worgs","Worgs","test",6,1,List.of(),List.of(start,kill,end),List.of(),List.of());
	}
}
