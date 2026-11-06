package com.aionemu.gameserver.services.ai;

import java.nio.file.*;
import java.util.*;
import com.aionemu.gameserver.GameServer;
import com.aionemu.gameserver.dao.*;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.AccountService;
import com.aionemu.gameserver.services.player.PlayerService;
import com.aionemu.gameserver.services.item.ItemFactory;
import com.aionemu.gameserver.services.ai.items.*;
import com.aionemu.gameserver.world.World;

/** Disposable native item/transaction/restart test. No fake connections or production databases. */
public final class CompanionC5RuntimeProbe {
	private static void check(boolean value, String message) {
		if (!value) throw new AssertionError(message);
		System.out.println("C5_CHECK PASS " + message);
	}
	private static void reject(Runnable action, String message) {
		try { action.run(); } catch (RuntimeException expected) { check(true, message); return; }
		throw new AssertionError(message);
	}
	public static void main(String[] args) throws Exception {
		var config = new Properties();
		try (var in = Files.newInputStream(Path.of("config/mygs.properties"))) { config.load(in); }
		if (!config.getProperty("database.url", "").startsWith("jdbc:mysql://127.0.0.1:53413/ai_c5_runtime?")) throw new IllegalStateException("Disposable C5 database guard");
		if (args.length != 1 || !List.of("create", "restore").contains(args[0])) throw new IllegalArgumentException();
		try {
			var main = GameServer.class.getDeclaredMethod("main"); main.setAccessible(true); main.invoke(null);
			run(args[0]); System.out.println("C5_RUNTIME_PASS phase=" + args[0]); GameServer.initShutdown(0, 0);
		} catch (Throwable failure) { failure.printStackTrace(); System.err.println("C5_RUNTIME_FAIL"); GameServer.initShutdown(1, 0); }
	}
	private static Player body(int ownerId) {
		var claim = new CompanionPersistenceDAO().loadCreationClaim(ownerId).orElseThrow();
		return PlayerService.getServerControlledPlayer(claim.companionPlayerId(), AccountService.loadServerControlledAccount(880003, "ai_c3_host", claim.companionPlayerId()), claim.blueprint().advancedClass());
	}
	private static void run(String phase) throws Exception {
		if (phase.equals("create")) CompanionC3RuntimeProbe.run("create");
		int ownerId = (int) CompanionC3RuntimeProbe.scalar("SELECT id FROM players WHERE name='Cthreeownera'");
		Player owner = CompanionC3RuntimeProbe.load(770031, "c3_owner_a", ownerId, false), body = body(ownerId);
		CompanionBodyDAO.verifyLoaded(body);
		if (phase.equals("restore")) {
			check(body.getInventory().getItemsByItemId(162000012).isEmpty(), "returned item absent from companion after JVM restart");
			Item returned = owner.getInventory().getItemsByItemId(162000012).getFirst();
			check(returned.getItemCount() == 2, "exact consumed stack survives handover and restart");
			check(CompanionC3RuntimeProbe.scalar("SELECT COUNT(*) FROM inventory WHERE item_id=162000012") == 1, "one native item instance after restart");
			check(CompanionC3RuntimeProbe.scalar("SELECT COUNT(*) FROM ai_companion_item_transfers WHERE owner_player_id=" + ownerId) == 2, "both transfer receipts durable");
			check(body.getItemCoolDowns().size() > 0, "native potion cooldown survives restart");
			CompanionC3RuntimeProbe.close(owner); CompanionC3RuntimeProbe.close(body); return;
		}
		Item potion = ItemFactory.newItem(162000012, 3);
		check(CompanionItemPolicy.potion(potion) != null, "native instant serum template supported");
		owner.getInventory().add(potion);
		check(InventoryDAO.store(owner), "native owner inventory saved");
		var transfer = new CompanionItemTransferDAO.Request(UUID.randomUUID(), owner, body, potion, 3, true);
		int otherId = (int) CompanionC3RuntimeProbe.scalar("SELECT id FROM players WHERE name='Cthreeownerb'");
		Player foreign = CompanionC3RuntimeProbe.load(770032, "c3_owner_b", otherId, false);
		var foreignRequest = new CompanionItemTransferDAO.Request(UUID.randomUUID(), foreign, body, potion, 3, true);
		reject(() -> CompanionItemTransferDAO.commit(foreignRequest), "another owner cannot operate this companion inventory");
		CompanionC3RuntimeProbe.close(foreign);
		CompanionC3RuntimeProbe.fixture("CREATE TRIGGER c5_fail_receipt BEFORE INSERT ON ai_companion_item_transfers FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='C5 injected receipt failure'");
		try { CompanionItemTransferDAO.commit(transfer); throw new AssertionError("Injected receipt error absent"); }
		catch (IllegalStateException expected) {
			check(expected.getCause() != null && expected.getCause().getMessage().contains("C5 injected receipt failure"), "receipt failure rolls back inventory handover");
		}
		check(CompanionC3RuntimeProbe.scalar("SELECT item_owner FROM inventory WHERE item_unique_id=" + potion.getObjectId()) == ownerId, "failed transaction retains original owner");
		CompanionC3RuntimeProbe.fixture("DROP TRIGGER c5_fail_receipt");
		try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
			var first = workers.submit(() -> CompanionItemTransferDAO.commit(transfer));
			var second = workers.submit(() -> CompanionItemTransferDAO.commit(transfer));
			first.get(10, java.util.concurrent.TimeUnit.SECONDS); second.get(10, java.util.concurrent.TimeUnit.SECONDS);
		}
		CompanionItemTransferDAO.commit(transfer);
		check(CompanionItemTransferDAO.receipt(ownerId, transfer.id()).startsWith("COMMITTED"), "same receipt returns committed without second execution");
		check(CompanionItemTransferDAO.receipt(ownerId + 1, transfer.id()).equals("NO_COMMITTED_RECEIPT"), "receipt isolated by owner");
		check(!InventoryDAO.store(potion, ownerId), "stale native autosave cannot reclaim transferred item");
		var repeated = new CompanionItemTransferDAO.Request(UUID.randomUUID(), owner, body, potion, 3, true);
		reject(() -> CompanionItemTransferDAO.commit(repeated), "second request cannot transfer same old instance again");
		CompanionC3RuntimeProbe.close(body); CompanionC3RuntimeProbe.close(owner);
		body = body(ownerId); owner = CompanionC3RuntimeProbe.load(770031, "c3_owner_a", ownerId, false);
		CompanionBodyDAO.verifyLoaded(body);
		Item received = body.getInventory().getItemByObjId(potion.getObjectId());
		check(received != null && received.getItemCount() == 3, "same item object ID loads in recipient inventory");
		check(!CompanionItemTransferDAO.isRetired(received), "fresh native item with same ID is not fenced");
		Item equipment = body.getEquipment().getEquippedItems().stream().filter(i -> !i.getItemTemplate().isStigma()).findFirst().orElseThrow();
		long slot = equipment.getEquipmentSlot();
		check(CompanionItems.equip(body, equipment.getObjectId(), 0), "native unequip moves original equipment into bag");
		check(CompanionItems.equip(body, equipment.getObjectId(), slot), "native equip restores stats and slot");
		World.getInstance().setPosition(body, 210010000, 1, 1231, 1042.47f, 144.726f, (byte) 33);
		World.getInstance().storeObject(body); World.getInstance().spawn(body); body.getLifeStats().cancelAllTasks();
		body.getLifeStats().setCurrentHp(1);
		check(!CompanionItems.use(body, received.getObjectId(), () -> false) && received.getItemCount() == 3, "disabled authorization consumes nothing");
		check(CompanionItems.use(body, received.getObjectId(), () -> true), "native serum use accepted through explicit authorization seam");
		check(received.getItemCount() == 2 && body.getLifeStats().getCurrentHp() > 1, "native cost consumes once and heals");
		check(!CompanionItems.use(body, received.getObjectId(), () -> true) && received.getItemCount() == 2, "native item cooldown rejects repeated use");
		// Extend only the fixture cooldown so it survives slow JVM startup.
		body.getItemCoolDowns().replaceAll((key, value) -> new com.aionemu.gameserver.model.items.ItemCooldown(System.currentTimeMillis() + 3_600_000, value.getUseDelay()));
		World.getInstance().removeObject(body); body.getLifeStats().cancelAllTasks();
		CompanionBodyDAO.save(CompanionBodyDAO.snapshot(ownerId, body));
		var giveBack = new CompanionItemTransferDAO.Request(UUID.randomUUID(), owner, body, received, 2, false);
		CompanionItemTransferDAO.commit(giveBack);
		check(CompanionC3RuntimeProbe.scalar("SELECT item_owner FROM inventory WHERE item_unique_id=" + received.getObjectId()) == ownerId, "return uses original object ID and consumed count");
		check(CompanionC3RuntimeProbe.scalar("SELECT COUNT(*) FROM player_quests WHERE player_id=" + body.getObjectId()) == 0, "item actions create no second quest journal");
		CompanionC3RuntimeProbe.close(owner); CompanionC3RuntimeProbe.close(body);
	}
}
