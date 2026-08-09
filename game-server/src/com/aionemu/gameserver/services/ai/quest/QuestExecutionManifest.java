package com.aionemu.gameserver.services.ai.quest;

import java.util.List;

import com.aionemu.gameserver.model.templates.spawns.SpawnTemplate;

public record QuestExecutionManifest(int questId, int mapId, List<Integer> targetIds, int requiredProgress, int startNpcId, int endNpcId,
	QuestSpawnFingerprint startSpawn, QuestSpawnFingerprint endSpawn, List<QuestSpawnFingerprint> approvedTargetSpawns) {

	public static final int POETA_MAP_ID = 210010000;
	public static final int START_END_NPC_ID = 203057;
	public static final int TARGET_1_ID = 210133;
	public static final int TARGET_2_ID = 210134;
	public static final int REQUIRED_PROGRESS = 3;

	public QuestExecutionManifest {
		targetIds = List.copyOf(targetIds);
		approvedTargetSpawns = List.copyOf(approvedTargetSpawns);
		if (questId != QuestExecutionAllowlist.FIRST_EXECUTION_QUEST_ID || mapId != POETA_MAP_ID
			|| !targetIds.equals(List.of(TARGET_1_ID, TARGET_2_ID)) || requiredProgress != REQUIRED_PROGRESS
			|| startNpcId != START_END_NPC_ID || endNpcId != START_END_NPC_ID || approvedTargetSpawns.size() != 39)
			throw new IllegalArgumentException("Invalid curated quest 1102 execution manifest");
	}

	public boolean matchesTarget(int targetTemplateId, SpawnTemplate spawn) {
		return targetIds.contains(targetTemplateId) && approvedTargetSpawns.stream().anyMatch(fingerprint -> fingerprint.npcId() == targetTemplateId
			&& fingerprint.matches(spawn));
	}

	public int approvedSpawnCount() {
		return approvedTargetSpawns.size();
	}

	public static QuestExecutionManifest curated1102() {
		QuestSpawnFingerprint mires = spawn(START_END_NPC_ID, 1141, 1032, 128.875f, 3);
		return new QuestExecutionManifest(QuestExecutionAllowlist.FIRST_EXECUTION_QUEST_ID, POETA_MAP_ID,
			List.of(TARGET_1_ID, TARGET_2_ID), REQUIRED_PROGRESS, START_END_NPC_ID, START_END_NPC_ID, mires, mires,
			List.of(
				spawn(TARGET_1_ID, 1038.58f, 989.604f, 129.484f, 93),
				spawn(TARGET_1_ID, 1050.87f, 996.053f, 131.596f, 111),
				spawn(TARGET_1_ID, 1052.9f, 1070.34f, 117.854f, 37),
				spawn(TARGET_1_ID, 1055.42f, 962.302f, 133.088f, 69),
				spawn(TARGET_1_ID, 1063, 1079.48f, 117.494f, 7),
				spawn(TARGET_1_ID, 1064.42f, 1064.27f, 123.261f, 29),
				spawn(TARGET_1_ID, 1072.1f, 1073.21f, 122.555f, 97),
				spawn(TARGET_1_ID, 1073.99f, 1057.03f, 125.867f, 30),
				spawn(TARGET_1_ID, 1097.29f, 977.352f, 130.113f, 118),
				spawn(TARGET_1_ID, 1098.26f, 1000.43f, 125.6f, 93),
				spawn(TARGET_1_ID, 1102.11f, 999.931f, 126.5f, 10),
				spawn(TARGET_1_ID, 1111.75f, 998.109f, 127.321f, 115),
				spawn(TARGET_1_ID, 1124.27f, 1018.68f, 127.584f, 115),
				spawn(TARGET_1_ID, 1153.9f, 995.87f, 137, 93),
				spawn(TARGET_1_ID, 1191.35f, 1136.9f, 139.6f, 71),
				spawn(TARGET_1_ID, 1199.99f, 1127.68f, 142.085f, 30),
				spawn(TARGET_2_ID, 1006.67f, 1214.79f, 101.966f, 15),
				spawn(TARGET_2_ID, 1010.19f, 1222.48f, 101.82f, 114),
				spawn(TARGET_2_ID, 1015.85f, 1119.46f, 111.815f, 67),
				spawn(TARGET_2_ID, 1018.38f, 1217.89f, 102.166f, 2),
				spawn(TARGET_2_ID, 1025.49f, 1118.9f, 115.368f, 0),
				spawn(TARGET_2_ID, 1029.75f, 1214.32f, 103.528f, 16),
				spawn(TARGET_2_ID, 1031.98f, 1135.02f, 115.289f, 82),
				spawn(TARGET_2_ID, 1038.61f, 1110.88f, 117.203f, 52),
				spawn(TARGET_2_ID, 1040.01f, 1139.44f, 117.269f, 35),
				spawn(TARGET_2_ID, 1040.21f, 1186.65f, 111.476f, 65),
				spawn(TARGET_2_ID, 1053.64f, 1184.29f, 114.463f, 100),
				spawn(TARGET_2_ID, 1054.72f, 1132.38f, 119.875f, 93),
				spawn(TARGET_2_ID, 1058.61f, 1176.98f, 116.347f, 49),
				spawn(TARGET_2_ID, 1064.15f, 1173.57f, 117.528f, 101),
				spawn(TARGET_2_ID, 1066.23f, 1125.47f, 120.116f, 114),
				spawn(TARGET_2_ID, 1068.78f, 1135.55f, 120.568f, 10),
				spawn(TARGET_2_ID, 1071.86f, 1126.84f, 120.116f, 91),
				spawn(TARGET_2_ID, 1073.19f, 1104.39f, 119.345f, 58),
				spawn(TARGET_2_ID, 1080.35f, 1111.82f, 121.305f, 82),
				spawn(TARGET_2_ID, 1108.24f, 1191.62f, 127.397f, 96),
				spawn(TARGET_2_ID, 1109.56f, 1173.8f, 126.625f, 97),
				spawn(TARGET_2_ID, 1111.61f, 1178.84f, 126.496f, 72),
				spawn(TARGET_2_ID, 1126.45f, 1182.56f, 129.329f, 14)));
	}

	private static QuestSpawnFingerprint spawn(int npcId, float x, float y, float z, int heading) {
		return new QuestSpawnFingerprint(POETA_MAP_ID, npcId, x, y, z, (byte) heading);
	}
}
