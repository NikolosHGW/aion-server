package com.aionemu.gameserver.services.ai.quest;

import java.util.*;

import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.templates.QuestTemplate;
import com.aionemu.gameserver.model.templates.npc.NpcTemplate;
import com.aionemu.gameserver.model.templates.quest.*;
import com.aionemu.gameserver.model.templates.spawns.SpawnGroup;
import com.aionemu.gameserver.model.templates.spawns.SpawnTemplate;
import com.aionemu.gameserver.model.templates.world.WorldMapTemplate;
import com.aionemu.gameserver.questEngine.QuestEngine;
import com.aionemu.gameserver.questEngine.handlers.models.MonsterHuntData;
import com.aionemu.gameserver.questEngine.handlers.models.XMLQuest;
import com.aionemu.gameserver.questEngine.handlers.template.MonsterHunt;
import com.aionemu.gameserver.utils.ChatUtil;

public final class QuestGoalCandidateProvider {

	public CandidateResult provide(Player owner, int questId) {
		QuestTemplate template = DataManager.QUEST_DATA.getQuestById(questId);
		if (template == null)
			return CandidateResult.reject(QuestGoalReason.UNKNOWN_QUEST);
		XMLQuest script = DataManager.XML_QUESTS.getQuest(questId);
		if (script == null || script.getClass() != MonsterHuntData.class
			|| QuestEngine.getInstance().getQuestHandlerType(questId) != MonsterHunt.class)
			return CandidateResult.reject(QuestGoalReason.UNSUPPORTED_HANDLER);
		MonsterHuntData hunt = (MonsterHuntData) script;

		String rawName = Objects.toString(template.getName(), "");
		String normalizedName = rawName.toLowerCase(Locale.ROOT);
		if (template.getCategory() != QuestCategory.QUEST && template.getCategory() != QuestCategory.IMPORTANT || template.isRestricted()
			|| template.getMinlevelPermitted() == 99 || normalizedName.contains("[test]") || normalizedName.contains("[event]"))
			return CandidateResult.reject(QuestGoalReason.UNSUPPORTED_CATEGORY);
		if (template.getMaxRepeatCount() != 1 || template.isTimeBased())
			return CandidateResult.reject(QuestGoalReason.UNSUPPORTED_REPEAT_POLICY);
		if (template.getNpcFactionId() != 0 || template.isMentor() || template.isTimer() || template.isDataDriven() || hunt.hasUnsupportedAutomaticStart())
			return CandidateResult.reject(QuestGoalReason.UNSUPPORTED_TEMPLATE_SHAPE);
		if (template.getCollectItems() != null || template.getInventoryItems() != null || template.getQuestWorkItems() != null
			|| !template.getQuestDrop().isEmpty() || template.getQuestKill().isEmpty())
			return CandidateResult.reject(QuestGoalReason.UNSUPPORTED_TEMPLATE_SHAPE);

		List<Integer> prerequisites = new ArrayList<>();
		for (XMLStartCondition condition : template.getXMLStartConditions()) {
			if (!condition.hasOnlyFinishedPreconditions())
				return CandidateResult.reject(QuestGoalReason.UNSUPPORTED_PRECONDITION);
			for (FinishedQuestCond finished : condition.getFinishedPreconditionsReadOnly())
				prerequisites.add(finished.getQuestId());
		}

		WorldMapTemplate map = DataManager.WORLD_MAPS_DATA.getTemplate(owner.getWorldId());
		if (map == null || map.isInstance())
			return CandidateResult.reject(QuestGoalReason.INSTANCE_MAP);

		List<Integer> startNpcIds = hunt.getStartNpcIdsReadOnly();
		List<Integer> endNpcIds = hunt.getEndNpcIdsReadOnly();
		if (startNpcIds.isEmpty() || endNpcIds.isEmpty() || startNpcIds.stream().anyMatch(id -> id <= 0) || endNpcIds.stream().anyMatch(id -> id <= 0))
			return CandidateResult.reject(QuestGoalReason.UNSUPPORTED_TEMPLATE_SHAPE);

		SpawnResult startSpawn = findNearest(owner, startNpcIds);
		if (!startSpawn.success())
			return CandidateResult.reject(startSpawn.reason());
		SpawnResult endSpawn = findNearest(owner, endNpcIds);
		if (!endSpawn.success())
			return CandidateResult.reject(endSpawn.reason());

		List<QuestGoalStep> steps = new ArrayList<>();
		steps.add(step(QuestGoalStep.Type.TALK_TO_START_NPC, startNpcIds, 1, startSpawn.spawn()));
		for (QuestKill kill : template.getQuestKill()) {
			List<Integer> targetIds = List.copyOf(kill.getNpcIds());
			if (kill.getQuestStep() != 0 || kill.getKillCount() <= 0 || targetIds.isEmpty() || targetIds.stream().anyMatch(id -> id <= 0))
				return CandidateResult.reject(QuestGoalReason.UNSUPPORTED_TEMPLATE_SHAPE);
			SpawnResult targetSpawn = findNearest(owner, targetIds);
			if (!targetSpawn.success())
				return CandidateResult.reject(targetSpawn.reason());
			steps.add(step(QuestGoalStep.Type.KILL_NPC_SET, targetIds, kill.getKillCount(), targetSpawn.spawn()));
		}
		steps.add(step(QuestGoalStep.Type.REPORT_TO_END_NPC, endNpcIds, 1, endSpawn.spawn()));

		double distanceSquared = distanceSquared(owner.getX(), owner.getY(), owner.getZ(), startSpawn.spawn());
		String localizedQuestName = template.getL10n() == null ? rawName : template.getL10n();
		QuestGoalPlan plan = new QuestGoalPlan(questId, rawName, localizedQuestName, "validated allowlist candidate", template.getMinlevelPermitted(),
			distanceSquared, prerequisites.stream().distinct().sorted().toList(), steps, rewards(template),
			List.of("reference coordinates only; no route has been built or proven reachable", "description unavailable in server data"));
		return CandidateResult.accept(plan);
	}

	public static List<Integer> parseAllowedQuestIds(String value) {
		if (value == null || value.isBlank())
			return List.of();
		LinkedHashSet<Integer> ids = new LinkedHashSet<>();
		for (String token : value.split(",")) {
			int id;
			try {
				id = Integer.parseInt(token.trim());
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException(QuestGoalReason.INVALID_ALLOWLIST.name(), e);
			}
			if (id <= 0 || !ids.add(id) || ids.size() > 3)
				throw new IllegalArgumentException(QuestGoalReason.INVALID_ALLOWLIST.name());
		}
		return ids.stream().sorted().toList();
	}

	private QuestGoalStep step(QuestGoalStep.Type type, List<Integer> targetIds, int count, StaticSpawn spawn) {
		List<String> names = targetIds.stream().map(this::localizedNpcName).toList();
		return new QuestGoalStep(type, targetIds, names, count,
			new QuestGoalStep.ReferenceCoordinate(spawn.mapId(), spawn.npcId(), spawn.staticId(), spawn.x(), spawn.y(), spawn.z()));
	}

	private String localizedNpcName(int npcId) {
		NpcTemplate npc = DataManager.NPC_DATA.getNpcTemplate(npcId);
		if (npc == null)
			return "npcId=" + npcId;
		return Objects.requireNonNullElse(npc.getL10n(), Objects.toString(npc.getName(), "npcId=" + npcId));
	}

	private SpawnResult findNearest(Player owner, List<Integer> npcIds) {
		List<StaticSpawn> spawns = new ArrayList<>();
		try {
			for (int npcId : npcIds) {
				if (DataManager.NPC_DATA.getNpcTemplate(npcId) == null)
					return SpawnResult.reject(QuestGoalReason.MISSING_NPC_TEMPLATE);
				List<SpawnGroup> groups = DataManager.SPAWNS_DATA.getSpawnsForNpc(owner.getWorldId(), npcId);
				if (groups.isEmpty())
					return SpawnResult.reject(QuestGoalReason.MISSING_SPAWN_METADATA);
				for (SpawnGroup group : groups) {
					if (group.getWorldId() != owner.getWorldId() || group.getNpcId() != npcId)
						return SpawnResult.reject(QuestGoalReason.INVALID_SPAWN_METADATA);
					for (SpawnTemplate spawn : group.getSpawnTemplates()) {
						if (spawn.isTemporarySpawn() || spawn.isEventSpawn())
							continue;
						if (!Float.isFinite(spawn.getX()) || !Float.isFinite(spawn.getY()) || !Float.isFinite(spawn.getZ()))
							return SpawnResult.reject(QuestGoalReason.INVALID_SPAWN_METADATA);
						spawns.add(new StaticSpawn(owner.getWorldId(), npcId, spawn.getStaticId(), spawn.getX(), spawn.getY(), spawn.getZ()));
					}
				}
			}
		} catch (RuntimeException e) {
			return SpawnResult.reject(QuestGoalReason.INVALID_SPAWN_METADATA);
		}
		return selectNearestStaticSpawn(owner.getX(), owner.getY(), owner.getZ(), spawns)
			.map(SpawnResult::accept).orElseGet(() -> SpawnResult.reject(QuestGoalReason.MISSING_SPAWN_METADATA));
	}

	public static Optional<StaticSpawn> selectNearestStaticSpawn(float ownerX, float ownerY, float ownerZ, Collection<StaticSpawn> spawns) {
		Comparator<StaticSpawn> stable = Comparator.comparingDouble((StaticSpawn spawn) -> distanceSquared(ownerX, ownerY, ownerZ, spawn))
			.thenComparingInt(spawn -> spawn.staticId() == 0 ? 1 : 0).thenComparingInt(StaticSpawn::staticId).thenComparingInt(StaticSpawn::mapId)
			.thenComparingInt(StaticSpawn::npcId).thenComparing(StaticSpawn::x).thenComparing(StaticSpawn::y).thenComparing(StaticSpawn::z);
		return spawns.stream().filter(spawn -> spawn.mapId() > 0 && spawn.npcId() > 0 && Float.isFinite(spawn.x()) && Float.isFinite(spawn.y())
			&& Float.isFinite(spawn.z())).min(stable);
	}

	private static double distanceSquared(float x, float y, float z, StaticSpawn spawn) {
		double dx = spawn.x() - x;
		double dy = spawn.y() - y;
		double dz = spawn.z() - z;
		return dx * dx + dy * dy + dz * dz;
	}

	private List<String> rewards(QuestTemplate template) {
		List<String> summaries = new ArrayList<>();
		for (int i = 0; i < template.getRewards().size(); i++) {
			Rewards reward = template.getRewards().get(i);
			StringBuilder summary = new StringBuilder("group ").append(i).append(": kinah=").append(reward.getKinah()).append(", exp=")
				.append(reward.getExp()).append(", ap=").append(reward.getAp()).append(", gp=").append(reward.getGp());
			for (QuestItems item : reward.getRewardItem())
				summary.append(", item=").append(ChatUtil.itemName(item.getItemId())).append('x').append(item.getCount());
			if (!reward.getSelectableRewardItem().isEmpty())
				summary.append(", selectableItems=").append(reward.getSelectableRewardItem().size());
			summaries.add(summary.toString());
		}
		return summaries;
	}

	public record StaticSpawn(int mapId, int npcId, int staticId, float x, float y, float z) {
	}

	public record CandidateResult(QuestGoalPlan plan, QuestGoalReason reason) {

		public static CandidateResult accept(QuestGoalPlan plan) {
			return new CandidateResult(plan, QuestGoalReason.ELIGIBLE);
		}

		public static CandidateResult reject(QuestGoalReason reason) {
			return new CandidateResult(null, reason);
		}

		public boolean success() {
			return plan != null;
		}
	}

	private record SpawnResult(StaticSpawn spawn, QuestGoalReason reason) {

		static SpawnResult accept(StaticSpawn spawn) {
			return new SpawnResult(spawn, QuestGoalReason.ELIGIBLE);
		}

		static SpawnResult reject(QuestGoalReason reason) {
			return new SpawnResult(null, reason);
		}

		boolean success() {
			return spawn != null;
		}
	}

}
