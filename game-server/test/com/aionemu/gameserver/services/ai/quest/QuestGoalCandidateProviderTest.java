package com.aionemu.gameserver.services.ai.quest;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.model.templates.quest.QuestKill;
import com.aionemu.gameserver.services.ai.quest.QuestGoalCandidateProvider.StaticSpawn;

class QuestGoalCandidateProviderTest {

	@Test
	void allowlistIsEmptyByDefaultShapeAndLimitedToThreeUniquePositiveIds() {
		assertEquals(List.of(), QuestGoalCandidateProvider.parseAllowedQuestIds(""));
		assertEquals(List.of(1102, 2102), QuestGoalCandidateProvider.parseAllowedQuestIds("2102, 1102"));
		assertThrows(IllegalArgumentException.class, () -> QuestGoalCandidateProvider.parseAllowedQuestIds("1102,1102"));
		assertThrows(IllegalArgumentException.class, () -> QuestGoalCandidateProvider.parseAllowedQuestIds("1,2,3,4"));
		assertThrows(IllegalArgumentException.class, () -> QuestGoalCandidateProvider.parseAllowedQuestIds("0"));
		assertThrows(IllegalArgumentException.class, () -> QuestGoalCandidateProvider.parseAllowedQuestIds("abc"));
	}

	@Test
	void nearestStaticSpawnSelectionIsStableAcrossInputIterationOrder() {
		StaticSpawn higherStaticId = new StaticSpawn(210010000, 203057, 20, 2, 0, 0);
		StaticSpawn lowerStaticId = new StaticSpawn(210010000, 203057, 10, -2, 0, 0);
		StaticSpawn farther = new StaticSpawn(210010000, 203057, 1, 10, 0, 0);

		assertEquals(lowerStaticId,
			QuestGoalCandidateProvider.selectNearestStaticSpawn(0, 0, 0, List.of(higherStaticId, farther, lowerStaticId)).orElseThrow());
		assertEquals(lowerStaticId,
			QuestGoalCandidateProvider.selectNearestStaticSpawn(0, 0, 0, new HashSet<>(List.of(lowerStaticId, farther, higherStaticId)))
				.orElseThrow());
	}

	@Test
	void questKillMetadataMayBeReadRepeatedlyWithoutChangingItsResult() throws Exception {
		QuestKill kill = new QuestKill();
		set(kill, "npcIds", new ArrayList<>(List.of(210133, 210134)));
		List<Integer> first = List.copyOf(kill.getNpcIds());

		List<Integer> second = List.copyOf(kill.getNpcIds());

		assertEquals(List.of(210133, 210134), first);
		assertEquals(first, second);
	}

	private void set(Object target, String fieldName, Object value) throws Exception {
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
