package com.aionemu.gameserver.services.ai.quest;

import com.aionemu.gameserver.questEngine.model.QuestState;
import com.aionemu.gameserver.questEngine.model.QuestStatus;

public record QuestNativeSnapshot(boolean present, QuestStatus status, int var0, int completeCount) {

	public static QuestNativeSnapshot readOnly(QuestState state) {
		return state == null ? absent() : new QuestNativeSnapshot(true, state.getStatus(), state.getQuestVarById(0), state.getCompleteCount());
	}

	public static QuestNativeSnapshot absent() {
		return new QuestNativeSnapshot(false, null, 0, 0);
	}
}
