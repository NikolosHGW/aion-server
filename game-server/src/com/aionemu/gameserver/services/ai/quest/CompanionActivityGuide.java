package com.aionemu.gameserver.services.ai.quest;

import com.aionemu.gameserver.questEngine.model.QuestStatus;
import com.aionemu.gameserver.utils.ChatUtil;

/** Presentation only: reads the owner's native journal, never changes it. */
public final class CompanionActivityGuide {
	private CompanionActivityGuide() { }
	public static String next(QuestGoalPlan plan, QuestNativeSnapshot nativeQuest) {
		String quest = ChatUtil.quest(plan.questId());
		if (nativeQuest.present() && nativeQuest.status() == QuestStatus.COMPLETE)
			return quest + ": задание завершено. Выберите новое занятие: //companion activities.";
		QuestGoalStep objective = plan.steps().get(1);
		if (!nativeQuest.present()) return quest + ": примите задание у " + destination(plan.steps().getFirst()) + ".";
		if (nativeQuest.status() == QuestStatus.REWARD || nativeQuest.status() == QuestStatus.START && nativeQuest.var0() >= objective.requiredCount())
			return quest + ": цель выполнена. Сдайте задание у " + destination(plan.steps().getLast()) + "; награду выбираете вы.";
		if (nativeQuest.status() != QuestStatus.START)
			return quest + ": состояние журнала не поддержано. Проверьте задание в своём журнале.";
		return quest + ": " + Math.clamp(nativeQuest.var0(), 0, objective.requiredCount()) + "/" + objective.requiredCount()
			+ ". Следующая цель: " + destination(objective) + ". Начните бой сами; помощь включается через //companion activity support.";
	}
	private static String destination(QuestGoalStep step) {
		var point = step.referenceCoordinate();
		return String.join(" / ", step.localizedTargetNames()) + " (" + Math.round(point.x()) + ", " + Math.round(point.y()) + ")";
	}
}
