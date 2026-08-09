package com.aionemu.gameserver.services.ai.quest;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.model.templates.spawns.SpawnGroup;
import com.aionemu.gameserver.model.templates.spawns.SpawnTemplate;

class QuestExecutionManifestTest {

	@Test
	void exactQuestAllowlistIsFailClosed() {
		assertTrue(QuestExecutionAllowlist.parse("").isEmpty());
		assertEquals(1, QuestExecutionAllowlist.parse("1102").size());
		assertTrue(QuestExecutionAllowlist.isExactFirstExecutionQuest("1102"));
		assertFalse(QuestExecutionAllowlist.isExactFirstExecutionQuest(""));
		assertFalse(QuestExecutionAllowlist.isExactFirstExecutionQuest("1102,1103"));
		assertFalse(QuestExecutionAllowlist.isExactFirstExecutionQuest("2102"));
	}

	@Test
	void curatedManifestContainsOnlyTwoTargetsAndThirtyNineNonWalkerSpawns() {
		QuestExecutionManifest manifest = QuestExecutionManifest.curated1102();
		assertEquals(1102, manifest.questId());
		assertEquals(210010000, manifest.mapId());
		assertEquals(3, manifest.requiredProgress());
		assertEquals(39, manifest.approvedSpawnCount());
		assertEquals(java.util.List.of(210133, 210134), manifest.targetIds());
	}

	@Test
	void exactStaticSpawnMatchesWhileWalkerWrongMapAndWrongTargetFail() {
		QuestExecutionManifest manifest = QuestExecutionManifest.curated1102();
		SpawnGroup approvedGroup = new SpawnGroup(210010000, 210133, 15, null);
		SpawnTemplate approved = new SpawnTemplate(approvedGroup, 1038.58f, 989.604f, 129.484f, (byte) 93, 0, null, 0);
		assertTrue(manifest.matchesTarget(210133, approved));

		SpawnGroup walkerGroup = new SpawnGroup(210010000, 210133, 15, null);
		SpawnTemplate walker = new SpawnTemplate(walkerGroup, 1038.58f, 989.604f, 129.484f, (byte) 93, 0, "walker", 0);
		assertFalse(manifest.matchesTarget(210133, walker));

		SpawnGroup wrongMapGroup = new SpawnGroup(220010000, 210133, 15, null);
		SpawnTemplate wrongMap = new SpawnTemplate(wrongMapGroup, 1038.58f, 989.604f, 129.484f, (byte) 93, 0, null, 0);
		assertFalse(manifest.matchesTarget(210133, wrongMap));
		assertFalse(manifest.matchesTarget(210134, approved));
	}
}
