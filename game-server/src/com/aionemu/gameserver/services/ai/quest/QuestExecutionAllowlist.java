package com.aionemu.gameserver.services.ai.quest;

import java.util.Set;

public final class QuestExecutionAllowlist {

	public static final int FIRST_EXECUTION_QUEST_ID = 1102;

	private QuestExecutionAllowlist() {
	}

	public static Set<Integer> parse(String value) {
		if (value == null || value.isBlank())
			return Set.of();
		String[] tokens = value.split(",");
		if (tokens.length != 1)
			throw new IllegalArgumentException("Stage 2D supports only exact quest 1102");
		try {
			int questId = Integer.parseInt(tokens[0].trim());
			if (questId != FIRST_EXECUTION_QUEST_ID)
				throw new IllegalArgumentException("Stage 2D supports only exact quest 1102");
			return Set.of(questId);
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid Stage 2D quest execution allowlist", e);
		}
	}

	public static boolean isExactFirstExecutionQuest(String value) {
		try {
			return parse(value).equals(Set.of(FIRST_EXECUTION_QUEST_ID));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
