package com.aionemu.gameserver.services.ai.quest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.templates.spawns.SpawnGroup;
import com.aionemu.gameserver.model.templates.spawns.SpawnTemplate;

public final class QuestExecutionPlanProvider {

	public QuestExecutionPlan build(QuestGoalPlan goalPlan) {
		QuestExecutionManifest manifest = QuestExecutionManifest.curated(goalPlan.questId());
		validateGoalPlan(goalPlan, manifest);
		validateStaticData(manifest);
		return new QuestExecutionPlan(goalPlan, manifest);
	}

	private void validateGoalPlan(QuestGoalPlan plan, QuestExecutionManifest manifest) {
		if (plan == null || plan.questId() != manifest.questId() || !plan.prerequisiteQuestIds().equals(manifest.questId() == 1102 ? List.of(1101) : List.of()) || plan.steps().size() != 3)
			throw new IllegalArgumentException("Quest execution plan is not the audited curated quest shape");
		QuestGoalStep start = plan.steps().get(0);
		QuestGoalStep kill = plan.steps().get(1);
		QuestGoalStep end = plan.steps().get(2);
		if (start.type() != QuestGoalStep.Type.TALK_TO_START_NPC || !start.targetIds().equals(List.of(manifest.startNpcId()))
			|| kill.type() != QuestGoalStep.Type.KILL_NPC_SET || !kill.targetIds().equals(manifest.targetIds())
			|| kill.requiredCount() != manifest.requiredProgress() || end.type() != QuestGoalStep.Type.REPORT_TO_END_NPC
			|| !end.targetIds().equals(List.of(manifest.endNpcId())) || plan.steps().stream().anyMatch(step -> step.referenceCoordinate().mapId() != manifest.mapId()))
			throw new IllegalArgumentException("Quest execution plan semantics changed from audited curated quest");
	}

	private void validateStaticData(QuestExecutionManifest manifest) {
		var kills = DataManager.QUEST_DATA.getQuestById(manifest.questId()).getQuestKill();
		if (kills.size() != 1 || kills.get(0).getVar() != 0 || kills.get(0).getSequenceNumber() != 0
			|| kills.get(0).getQuestStep() != 0 || kills.get(0).getKillCount() != manifest.requiredProgress()
			|| !kills.get(0).getNpcIds().equals(manifest.targetIds()))
			throw new IllegalStateException("Audited native quest counter changed");
		Set<QuestSpawnFingerprint> actualTargets = new HashSet<>();
		for (int npcId : manifest.targetIds()) {
			for (SpawnGroup group : DataManager.SPAWNS_DATA.getSpawnsForNpc(manifest.mapId(), npcId)) {
				for (SpawnTemplate spawn : group.getSpawnTemplates()) {
					if (!spawn.isTemporarySpawn() && !spawn.isEventSpawn() && spawn.getWalkerId() == null && spawn.getRandomWalkRange() == 0)
						actualTargets.add(fingerprint(spawn));
				}
			}
		}
		if (!actualTargets.equals(Set.copyOf(manifest.approvedTargetSpawns())))
			throw new IllegalStateException("Curated quest static target spawn manifest no longer matches audited data");

		Set<QuestSpawnFingerprint> actualStartEnd = new HashSet<>();
		for (SpawnGroup group : DataManager.SPAWNS_DATA.getSpawnsForNpc(manifest.mapId(), manifest.startNpcId())) {
			for (SpawnTemplate spawn : group.getSpawnTemplates()) {
				if (!spawn.isTemporarySpawn() && !spawn.isEventSpawn() && spawn.getWalkerId() == null && spawn.getRandomWalkRange() == 0)
					actualStartEnd.add(fingerprint(spawn));
			}
		}
		if (!actualStartEnd.equals(Set.of(manifest.startSpawn())) || !manifest.startSpawn().equals(manifest.endSpawn()))
			throw new IllegalStateException("Curated quest start/end spawn manifest no longer matches audited data");
	}

	private QuestSpawnFingerprint fingerprint(SpawnTemplate spawn) {
		return new QuestSpawnFingerprint(spawn.getWorldId(), spawn.getNpcId(), spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getHeading());
	}
}
