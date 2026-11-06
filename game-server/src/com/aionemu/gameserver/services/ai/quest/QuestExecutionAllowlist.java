package com.aionemu.gameserver.services.ai.quest;

import java.util.Set;
import java.util.HashSet;

/** Explicitly audited single-counter hunts. Config cannot authorize an arbitrary quest. */
public final class QuestExecutionAllowlist {
	public static final int FIRST_EXECUTION_QUEST_ID = 1102;
	public static final int SECOND_EXECUTION_QUEST_ID = 1120;
	private static final Set<Integer> AUDITED = Set.of(FIRST_EXECUTION_QUEST_ID, SECOND_EXECUTION_QUEST_ID);
	private QuestExecutionAllowlist() { }
	public static Set<Integer> parse(String value) {
		if (value == null || value.isBlank()) return Set.of();
		Set<Integer> ids = new HashSet<>();
		for (String token : value.split(",", -1)) {
			int id = Integer.parseInt(token.trim());
			if (!AUDITED.contains(id) || !ids.add(id)) throw new IllegalArgumentException("Only unique audited quests 1102 and 1120 are supported");
		}
		return Set.copyOf(ids);
	}
	public static boolean allows(String value, int questId) {
		try { return parse(value).contains(questId); } catch (IllegalArgumentException e) { return false; }
	}
	public static boolean isValid(String value) {
		try { return !parse(value).isEmpty(); } catch (IllegalArgumentException e) { return false; }
	}
	public static boolean isExactFirstExecutionQuest(String value) {
		try { return parse(value).equals(Set.of(FIRST_EXECUTION_QUEST_ID)); } catch (IllegalArgumentException e) { return false; }
	}
}
