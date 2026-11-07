package admincommands;

import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.ai.CompanionService;
import com.aionemu.gameserver.services.ai.ServerControlledPlayer;
import com.aionemu.gameserver.services.ai.quest.QuestGoalCommandFormatter;
import com.aionemu.gameserver.services.ai.quest.QuestGoalProposalResult;
import com.aionemu.gameserver.utils.chathandlers.AdminCommand;

public class Companion extends AdminCommand {

	public Companion() {
		super("companion", "Controls your personal companion.", """
			<sources> - List your account characters as appearance/class templates.
			<create SourceName [Nickname|-] [Specialization]> - Create once; '-' keeps the source nickname. Basic classes need specialization.
			<summon> - Spawn your already-created persistent companion.
			<move here|target> - Walk to a checked local destination and stay.
			<follow> - Resume following you.
			<stay> - Stop and remain in the world.
			<assist on|off|status> - Arm or disarm one-hit reactions to owner attack events.
			<dismiss> - Dismiss your companion; keep its ownership and saved goal.
			<status> - Show owner, lifecycle, movement and world diagnostics.
			<items list|cancel|receipt UUID> - Inspect inventory or pending handover.
			<items use|unequip|give|take ObjectId> - Use potion, unequip, or queue whole item for logout.
			<items equip ObjectId SlotMask> - Equip through native class/level rules.
			<activities> - List companion activities and available audited quests.
			<activity escort|support|rest|quest ID> - Choose an activity.
			<next> - Explain the next step from your native quest journal.
			<story suggest|status> - Recall a confirmed shared episode and request one suggestion.
			<story decline|reset OfferUUID> - Remember a refusal or explicitly allow a new suggestion.
			<today> - Offer one read-only quest goal from the configured allowlist.
			<goal choose|status|clear> - Manage the persistent quest goal intent.
			""");
	}

	@Override
	public void execute(Player admin, String... params) {
		try {
			executeOwned(admin, params);
		} catch (IllegalStateException | IllegalArgumentException e) {
			sendInfo(admin, e.getMessage());
		}
	}

	private void executeOwned(Player admin, String... params) {
		if (params.length >= 2 && params[0].equalsIgnoreCase("story")) {
			var service = CompanionService.getInstance();
			String action = params[1].toLowerCase(java.util.Locale.ROOT);
			if (params.length == 2 && action.equals("status")) service.storyStatus(admin).lines().forEach(line -> sendInfo(admin, line));
			else if (params.length == 2 && action.equals("suggest")) service.storySuggest(admin).lines().forEach(line -> sendInfo(admin, line));
			else if (params.length == 3 && java.util.Set.of("decline", "reset").contains(action))
				service.storyDecision(admin, java.util.UUID.fromString(params[2]), action.equals("reset")).lines().forEach(line -> sendInfo(admin, line));
			else sendInfo(admin);
			return;
		}
		if (params.length >= 2 && params[0].equalsIgnoreCase("activity")) {
			String kind = params[1].toLowerCase(java.util.Locale.ROOT);
			if (kind.equals("quest") && params.length == 3)
				sendInfo(admin, CompanionService.getInstance().activity(admin, kind, Integer.parseInt(params[2])));
			else if (params.length == 2 && java.util.Set.of("escort", "support", "rest").contains(kind))
				sendInfo(admin, CompanionService.getInstance().activity(admin, kind, 0));
			else sendInfo(admin);
			return;
		}
		if (params.length == 2 && params[0].equalsIgnoreCase("move") && java.util.Set.of("here", "target").contains(params[1].toLowerCase())) {
			sendInfo(admin, CompanionService.getInstance().navigate(admin, params[1].equalsIgnoreCase("target"))); return;
		}
		if (params.length >= 2 && params[0].equalsIgnoreCase("items")) {
			var service = CompanionService.getInstance();
			String action = params[1].toLowerCase();
			if (params.length == 2 && action.equals("list")) service.items(admin).forEach(line -> sendInfo(admin, line));
			else if (params.length == 2 && action.equals("cancel")) sendInfo(admin, "Cancelled pending handover: " + service.cancelItemTransfer(admin));
			else if (params.length == 3 && action.equals("receipt")) sendInfo(admin,
				com.aionemu.gameserver.dao.CompanionItemTransferDAO.receipt(admin.getObjectId(), java.util.UUID.fromString(params[2])));
			else if (params.length == 3 && java.util.Set.of("use", "unequip", "give", "take").contains(action)
				|| params.length == 4 && action.equals("equip"))
				sendInfo(admin, service.itemAction(admin, action, Integer.parseInt(params[2]), params.length == 4 ? Long.parseLong(params[3]) : 0));
			else sendInfo(admin);
			return;
		}
		if (params.length >= 2 && params.length <= 4 && params[0].equalsIgnoreCase("create")) {
			var service = CompanionService.getInstance();
			var result = service.create(admin, params[1], params.length >= 3 && !params[2].equals("-") ? params[2] : null,
				params.length == 4 ? params[3] : null);
			sendInfo(admin, "Companion create: " + result.status() + ". Use //companion summon.");
			sendInfo(admin, service.getStatus(admin));
			return;
		}
		if (params.length == 2 && params[0].equalsIgnoreCase("assist")) {
			executeAssist(admin, params[1]);
			return;
		}
		if (params.length == 2 && params[0].equalsIgnoreCase("goal")) {
			executeGoal(admin, params[1]);
			return;
		}
		if (params.length != 1) {
			sendInfo(admin);
			return;
		}

		CompanionService service = CompanionService.getInstance();
		switch (params[0].toLowerCase()) {
			case "activities" -> service.activities(admin).forEach(line -> sendInfo(admin, line));
			case "next" -> sendInfo(admin, service.nextActivity(admin));
			case "sources" -> {
				for (var source : service.sources(admin))
					sendInfo(admin, source.name() + " | " + source.race() + " | " + source.gender() + " | " + source.playerClass());
			}
			case "create" -> {
				var result = service.create(admin);
				sendInfo(admin, "Companion create: " + result.status() + ", companionObjectId=" + result.body().playerId()
					+ ", companionDbName=" + result.body().databaseName() + ". Use //companion summon.");
				sendInfo(admin, service.getStatus(admin));
			}
			case "summon" -> {
				ServerControlledPlayer companion = service.summon(admin);
				sendInfo(admin, "Summoned runtime companion " + companion.getRuntimeName() + " (object ID " + companion.getObjectId() + ").");
				sendInfo(admin, service.getStatus(admin));
			}
			case "follow" -> {
				service.follow(admin);
				sendInfo(admin, "Companion mode set to FOLLOWING.");
				sendInfo(admin, service.getStatus(admin));
			}
			case "stay" -> {
				service.stay(admin);
				sendInfo(admin, "Companion mode set to STAYING.");
				sendInfo(admin, service.getStatus(admin));
			}
			case "dismiss" -> {
				boolean removed = service.dismiss(admin, "owner-command:" + admin.getName());
				sendInfo(admin, removed ? "Your companion was dismissed; ownership and saved goal remain." : "Your companion is already absent.");
				sendInfo(admin, service.getStatus(admin));
			}
			case "status" -> sendInfo(admin, service.getStatus(admin));
			case "today" -> {
				QuestGoalProposalResult result = service.proposeGoal(admin);
				sendInfo(admin, QuestGoalCommandFormatter.formatProposal(result));
			}
			default -> sendInfo(admin);
		}
	}

	private void executeAssist(Player admin, String action) {
		CompanionService service = CompanionService.getInstance();
		switch (action.toLowerCase()) {
			case "on" -> {
				boolean changed = service.assistOn(admin);
				sendInfo(admin, changed ? "Companion assist enabled; waiting for your next real attack event."
					: "Companion assist is already enabled.");
				sendInfo(admin, service.getCombatStatus(admin));
			}
			case "off" -> {
				boolean changed = service.assistOff(admin);
				sendInfo(admin, changed ? "Companion assist disabled and pending combat event cleared."
					: "Companion assist is already disabled.");
				sendInfo(admin, service.getCombatStatus(admin));
			}
			case "status" -> sendInfo(admin, service.getCombatStatus(admin));
			default -> sendInfo(admin);
		}
	}

	private void executeGoal(Player admin, String action) {
		CompanionService service = CompanionService.getInstance();
		switch (action.toLowerCase()) {
			case "choose" -> sendInfo(admin, QuestGoalCommandFormatter.formatChoice(service.chooseGoal(admin)));
			case "status" -> sendInfo(admin, service.getGoalStatus(admin));
			case "clear" -> sendInfo(admin, service.clearGoal(admin) ? "Runtime quest goal cleared." : "Runtime quest goal already clear.");
			default -> sendInfo(admin);
		}
	}
}
