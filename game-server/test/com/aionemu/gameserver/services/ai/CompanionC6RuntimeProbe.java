package com.aionemu.gameserver.services.ai;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import com.aionemu.gameserver.GameServer;
import com.aionemu.gameserver.configs.main.GeoDataConfig;
import com.aionemu.gameserver.dao.CompanionPersistenceDAO;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.AccountService;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.services.ai.action.TrackedMovementGateway;
import com.aionemu.gameserver.taskmanager.tasks.PlayerMoveTaskManager;
import com.aionemu.gameserver.utils.PositionUtil;
import com.aionemu.gameserver.world.World;

/** Reuses only the disposable C5 fixture database. Native movement/geodata, explicit connected-owner seam. */
public final class CompanionC6RuntimeProbe {
	private static void check(boolean value, String text) {
		if (!value) throw new AssertionError(text); System.out.println("C6_CHECK PASS " + text);
	}
	public static void main(String[] args) throws Exception {
		var config = new Properties();
		try (var in = Files.newInputStream(Path.of("config/mygs.properties"))) { config.load(in); }
		if (!config.getProperty("database.url", "").startsWith("jdbc:mysql://127.0.0.1:53413/ai_c5_runtime?")) throw new IllegalStateException("Disposable C6 database guard");
		try {
			var main = GameServer.class.getDeclaredMethod("main"); main.setAccessible(true); main.invoke(null);
			run(); System.out.println("C6_RUNTIME_PASS"); GameServer.initShutdown(0, 0);
		} catch (Throwable failure) { failure.printStackTrace(); System.err.println("C6_RUNTIME_FAIL"); GameServer.initShutdown(1, 0); }
	}
	private static void spawn(Player actor, float x) {
		World.getInstance().setPosition(actor, 210010000, 1, x, 1042.47f, 144.726f, (byte) 33);
		World.getInstance().storeObject(actor); World.getInstance().spawn(actor); actor.getLifeStats().cancelAllTasks();
	}
	static void run() throws Exception {
		int ownerId = (int) CompanionC3RuntimeProbe.scalar("SELECT id FROM players WHERE name='Cthreeownera'");
		var claim = new CompanionPersistenceDAO().loadCreationClaim(ownerId).orElseThrow();
		Player owner = CompanionC3RuntimeProbe.load(770031, "c3_owner_a", ownerId, false);
		Player body = PlayerService.getServerControlledPlayer(claim.companionPlayerId(), AccountService.loadServerControlledAccount(880003, "ai_c3_host", claim.companionPlayerId()), claim.blueprint().advancedClass());
		spawn(owner, 1231); spawn(body, 1230);
		var connected = new AtomicBoolean(true);
		CompanionContext nativeContext = new PlayerCompanionContext(owner, body);
		CompanionContext context = (CompanionContext) java.lang.reflect.Proxy.newProxyInstance(CompanionContext.class.getClassLoader(),
			new Class<?>[] { CompanionContext.class }, (proxy, method, arguments) -> method.getName().equals("ownerConnected") ? connected.get() : method.invoke(nativeContext, arguments));
		var nativeGateway = new DefaultPlayerActionGateway(body, owner);
		var movement = new TrackedMovementGateway(nativeGateway, connected::get, System::currentTimeMillis,
			body::getX, body::getY, body::getZ, () -> body.getMoveController().isInMove());
		var removed = new AtomicReference<String>();
		var controllerRef = new AtomicReference<CompanionController>();
		var wrapperRef = new AtomicReference<ServerControlledPlayer>();
		var controller = new CompanionController(context, movement, new CompanionFollowSettings(6, 3, 2, 400, 1000), reason -> {
			controllerRef.get().beginRemoval(); movement.close();
			World.getInstance().removeObject(body); SyntheticPlayerRuntime.getInstance().getRegistry().unregister(wrapperRef.get()); removed.set(reason);
		}, System::currentTimeMillis, movement::poll);
		controllerRef.set(controller);
		var wrapper = new ServerControlledPlayer(body, body.getCommonData().getName(), claim.blueprint().displayName(), SyntheticPlayerRole.COMPANION, controller, () -> true, removed::set);
		wrapperRef.set(wrapper); SyntheticPlayerRuntime.getInstance().getRegistry().register(wrapper); wrapper.markRegistered(); wrapper.activate();
		var result = controller.navigate(1232, 1042.47f, 144.726f);
		check(result.success(), "geodata accepts short local ground segment: " + result.reason());
		long deadline = System.currentTimeMillis() + 10_000;
		while (controller.getMode() == CompanionMode.NAVIGATING && System.currentTimeMillis() < deadline) { controller.tick(); Thread.sleep(100); }
		check(controller.getMode() == CompanionMode.STAYING && PositionUtil.getDistance(body.getX(), body.getY(), body.getZ(), result.resolvedX(), result.resolvedY(), result.resolvedZ()) <= 0.5,
			"native move task reaches destination and stays");
		check(!PlayerMoveTaskManager.getInstance().contains(body), "arrival removes native movement task");
		check(controller.navigate(1230, 1042.47f, 144.726f).success(), "second local segment starts");
		controller.cancelNavigation(); check(!PlayerMoveTaskManager.getInstance().contains(body), "rollback/cancel stops native movement immediately");
		var geoMode = GeoDataConfig.MODE;
		try {
			GeoDataConfig.MODE = GeoDataConfig.Mode.OFF;
			check(!controller.navigate(1230, 1042.47f, 144.726f).success(), "disabled geodata refuses navigation");
		} finally { GeoDataConfig.MODE = geoMode; }
		connected.set(false); controller.tick();
		check("owner-disconnected".equals(removed.get()) && !World.getInstance().isInWorld(body.getObjectId())
			&& !PlayerMoveTaskManager.getInstance().contains(body) && !SyntheticPlayerRuntime.getInstance().getRegistry().contains(body), "owner departure leaves no world, movement or registry body");
		World.getInstance().removeObject(owner); CompanionC3RuntimeProbe.close(owner); CompanionC3RuntimeProbe.close(body);
	}
}
