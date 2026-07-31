package admincommands;

import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.ai.ServerControlledPlayer;
import com.aionemu.gameserver.services.ai.ServerControlledPlayerService;
import com.aionemu.gameserver.utils.chathandlers.AdminCommand;

public class AiPlayer extends AdminCommand {

	public AiPlayer() {
		super("aiplayer", "Controls the single stage-1 server-controlled player.");
		setSyntaxInfo(
			"<spawn> - Load, validate and spawn the configured runtime-only template.",
			"<despawn> - Safely remove the runtime player without saving.",
			"<status> - Show lifecycle, world, scheduler and movement state.");
	}

	@Override
	public void execute(Player admin, String... params) {
		if (params.length != 1) {
			sendInfo(admin);
			return;
		}

		ServerControlledPlayerService service = ServerControlledPlayerService.getInstance();
		switch (params[0].toLowerCase()) {
			case "spawn" -> {
				ServerControlledPlayer player = service.spawn(admin);
				sendInfo(admin, "Spawned runtime player " + player.getRuntimeName() + " (object ID " + player.getObjectId() + ").");
				sendInfo(admin, service.getStatus());
			}
			case "despawn" -> {
				boolean removed = service.despawn("admin-command:" + admin.getName());
				sendInfo(admin, removed ? "Synthetic player removed without persistence." : "Synthetic player is already absent.");
				sendInfo(admin, service.getStatus());
			}
			case "status" -> sendInfo(admin, service.getStatus());
			default -> sendInfo(admin);
		}
	}
}
