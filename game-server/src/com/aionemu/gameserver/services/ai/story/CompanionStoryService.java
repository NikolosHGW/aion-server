package com.aionemu.gameserver.services.ai.story;

import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.aionemu.gameserver.configs.main.AIConfig;
import com.aionemu.gameserver.dao.CompanionStoryDAO;
import com.aionemu.gameserver.services.ai.memory.CompanionEpisodeService;
import com.aionemu.gameserver.services.ai.persistence.QuestGoalSemanticFingerprint;
import static com.aionemu.gameserver.services.ai.story.CompanionStory.*;

/** Explicit owner requests only: no login hook, periodic proposals, native actions, LLM or spending. */
public final class CompanionStoryService {
	private static final Logger log=LoggerFactory.getLogger(CompanionStoryService.class);
	private static final String HISTORY="Помню, как вы сдали задание 1102: я присутствовал при сдаче.";
	private final CompanionStoryStore store;
	private final BooleanSupplier enabled;
	public CompanionStoryService(CompanionStoryStore store, BooleanSupplier enabled) { this.store=store; this.enabled=enabled; }
	public static CompanionStoryService getInstance() { return Holder.INSTANCE; }
	private static final class Holder {
		static final CompanionStoryService INSTANCE=new CompanionStoryService(new CompanionStoryDAO(),CompanionStoryService::flagsEnabled);
	}
	public static boolean flagsEnabled() {
		return AIConfig.COMPANION_STORY_ENABLED && CompanionEpisodeService.flagsEnabled() && AIConfig.COMPANION_GOAL_PERSISTENCE_ENABLED;
	}
	public Reply status(Owner owner) {
		if (!enabled.getAsBoolean()) return disabled();
		try {
			var context=store.read(owner);
			return context.isEmpty() ? noMemory() : describe(context.get().offer());
		} catch (Exception failure) { return unavailable(failure); }
	}
	public Reply suggest(Actor actor, Supplier<Candidate> candidate) {
		if (!enabled.getAsBoolean()) return disabled();
		try {
			var context=store.read(actor.owner());
			if (context.isEmpty()) return noMemory();
			var view=context.get();
			if (!view.episode().belongsTo(actor)) throw new IllegalStateException("Story actor mismatch");
			if (view.declined()) return describe(view.offer());
			Candidate advice=candidate.get();
			if (advice.plan()==null) return Reply.of(Kind.READY,null,HISTORY,advice.unavailableReason());
			if (!enabled.getAsBoolean()) return disabled();
			Offer offer=store.offer(actor,view.episode(),QuestGoalSemanticFingerprint.sha256(advice.plan()));
			return describe(offer); // A concurrent refusal wins over a repeated suggestion.
		} catch (Exception failure) { return unavailable(failure); }
	}
	public Reply decide(Actor actor, UUID token, Decision decision) {
		if (!enabled.getAsBoolean()) return disabled();
		try {
			var result=store.decide(actor,token,decision);
			return result.isEmpty() ? Reply.of(Kind.STALE,null,"Это предложение уже недействительно. Проверьте //companion story status.") : describe(result.get());
		} catch (Exception failure) { return unavailable(failure); }
	}
	/** Existing today suggestions honor P1 refusal; explicit activity/quest commands remain available. */
	public Set<Integer> declinedQuests(Actor actor) {
		if (!enabled.getAsBoolean()) return Set.of();
		try {
			var view=store.read(actor.owner());
			if (view.isEmpty()) return Set.of();
			if (!view.get().episode().belongsTo(actor)) throw new IllegalStateException("Story actor mismatch");
			return view.get().declined() ? Set.of(QUEST) : Set.of();
		} catch (Exception failure) {
			log.warn("P1 preference read failed; no new recommendation",failure);
			throw new IllegalStateException("Не удалось проверить сохранённый отказ; новое предложение пока не выбрано.",failure);
		}
	}
	private Reply describe(Offer offer) {
		if (offer==null) return Reply.of(Kind.READY,null,HISTORY,"Можно запросить предложение: //companion story suggest.");
		return switch(offer.decision()) {
			case OFFERED -> Reply.of(Kind.OFFERED,offer.token(),HISTORY,
				"Предложение: помочь с заданием 1120. Доступность проверяется снова при выборе: //companion activity quest 1120.",
				"Отказаться: //companion story decline "+offer.token());
			case DECLINED -> Reply.of(Kind.DECLINED,offer.token(),HISTORY,
				"Вы отказались от предложения 1120. Больше не предлагаю его; другое занятие можно выбрать через //companion activities.",
				"Разрешить предлагать снова: //companion story reset "+offer.token());
			case RESET -> Reply.of(Kind.RESET,offer.token(),HISTORY,
				"Предложение и отказ сброшены. Для нового предложения: //companion story suggest. Текущее занятие сохранено.");
		};
	}
	private Reply disabled() { return Reply.of(Kind.DISABLED,null,"Предложения по личной истории сейчас отключены."); }
	private Reply noMemory() { return Reply.of(Kind.NO_MEMORY,null,"У меня пока нет подтверждённой памяти о совместной сдаче 1102."); }
	private Reply unavailable(Exception failure) {
		log.warn("P1 request failed; no success is claimed",failure);
		return Reply.of(Kind.UNAVAILABLE,null,"Память сейчас недоступна. Изменения не подтверждены; проверьте status перед повтором.");
	}
}
