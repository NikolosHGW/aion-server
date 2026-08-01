package admincommands;

import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.ai.CompanionService;
import com.aionemu.gameserver.services.ai.ServerControlledPlayer;
import com.aionemu.gameserver.services.ai.quest.QuestGoalCommandFormatter;
import com.aionemu.gameserver.services.ai.quest.QuestGoalPlan;
import com.aionemu.gameserver.utils.chathandlers.AdminCommand;

public class Companion extends AdminCommand {

	public Companion() {
		super("companion", "Controls the single runtime companion.");
		setSyntaxInfo(
			"<summon> - Spawn the configured companion template and bind it to you.",
			"<follow> - Resume following you.",
			"<stay> - Stop and remain in the world.",
			"<dismiss> - Remove the companion without persistence.",
			"<status> - Show owner, lifecycle, movement and world diagnostics.",
			"<today> - Offer one read-only quest goal from the configured allowlist.",
			"<goal choose|status|clear> - Manage the runtime-only quest goal offer.");
	}

	@Override
	public void execute(Player admin, String... params) {
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
			case "summon" -> {
				ServerControlledPlayer companion = service.summon(admin);
				sendInfo(admin, "Summoned runtime companion " + companion.getRuntimeName() + " (object ID " + companion.getObjectId() + ").");
				sendInfo(admin, service.getStatus());
			}
			case "follow" -> {
				service.follow(admin);
				sendInfo(admin, "Companion mode set to FOLLOWING.");
				sendInfo(admin, service.getStatus());
			}
			case "stay" -> {
				service.stay(admin);
				sendInfo(admin, "Companion mode set to STAYING.");
				sendInfo(admin, service.getStatus());
			}
			case "dismiss" -> {
				boolean removed = service.dismiss(admin, "admin-command:" + admin.getName());
				sendInfo(admin, removed ? "Companion removed without persistence." : "Companion is already absent.");
				sendInfo(admin, service.getStatus());
			}
			case "status" -> sendInfo(admin, service.getStatus());
			case "today" -> {
				QuestGoalPlan plan = service.proposeGoal(admin);
				sendInfo(admin, QuestGoalCommandFormatter.formatProposal(plan));
			}
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
