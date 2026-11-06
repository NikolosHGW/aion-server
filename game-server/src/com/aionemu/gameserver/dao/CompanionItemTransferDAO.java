package com.aionemu.gameserver.dao;

import java.sql.*;
import java.util.*;

import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.ai.items.CompanionItemPolicy;
import com.aionemu.gameserver.world.World;

/** One whole native inventory row changes owner, together with its durable receipt. Never copies an item. */
public final class CompanionItemTransferDAO {
	private CompanionItemTransferDAO() { }
	public record Request(UUID id, Player owner, Player body, Item item, long count, boolean toBody) { }
	// AionObject.equals compares object IDs, so WeakHashMap<Item,...> would also fence a fresh login's item.
	private static final List<java.lang.ref.WeakReference<Item>> retired = new ArrayList<>();

	/** A write fence around handovers excludes native autosave batches; ordinary saves remain concurrent. */
	public static boolean isRetired(Item item) {
		synchronized (retired) {
			retired.removeIf(reference -> reference.get() == null);
			return retired.stream().anyMatch(reference -> reference.get() == item);
		}
	}

	public static String receipt(int ownerId, UUID requestId) {
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement statement = con.prepareStatement(
			"SELECT companion_player_id,item_unique_id,to_companion FROM ai_companion_item_transfers WHERE owner_player_id=? AND request_id=?")) {
			statement.setInt(1, ownerId); statement.setString(2, requestId.toString());
			try (var rows = statement.executeQuery()) {
				return rows.next() ? "COMMITTED body=" + rows.getInt(1) + " item=" + rows.getInt(2) + " toCompanion=" + rows.getBoolean(3) : "NO_COMMITTED_RECEIPT";
			}
		} catch (SQLException e) { throw new IllegalStateException("Transfer receipt unavailable", e); }
	}

	public static void commit(Request request) {
		var fence = InventoryDAO.ITEM_HANDOVER_LOCK.writeLock();
		fence.lock();
		try {
			Player owner = request.owner(), body = request.body();
			for (Player player : List.of(owner, body))
				if (player.isOnline() || player.isInWorld() || player.isSpawned() || World.getInstance().isInWorld(player.getObjectId()))
					throw new IllegalStateException("Transfer requires both actors to be detached");
			Player source = request.toBody() ? owner : body, target = request.toBody() ? body : owner;
			Item item = request.item();
			try (Connection con = DatabaseFactory.getConnection()) {
				con.setAutoCommit(false);
				try {
					// Character rows also serialize ownership checkpoints; stable order avoids inverted transfers.
					try (PreparedStatement lock = con.prepareStatement("SELECT id,online FROM players WHERE id IN (?,?) ORDER BY id FOR UPDATE")) {
						lock.setInt(1, owner.getObjectId()); lock.setInt(2, body.getObjectId());
						try (var rows = lock.executeQuery()) {
							int count = 0; while (rows.next()) { if (rows.getBoolean(2)) throw new SQLException("Actor online"); count++; }
							if (count != 2) throw new SQLException("Missing actor");
						}
					}
					try (PreparedStatement binding = con.prepareStatement("""
						SELECT b.companion_player_id FROM ai_companions b JOIN ai_companion_creation_claims c
						ON c.owner_player_id=b.owner_player_id JOIN players p ON p.id=c.companion_player_id
						WHERE b.owner_player_id=? AND b.companion_player_id=? AND b.role='PERSONAL_COMPANION'
						AND c.companion_player_id=b.companion_player_id AND c.host_account_id=p.account_id
						AND c.host_account_name=p.account_name AND c.body_name=p.name FOR UPDATE
						""")) {
						binding.setInt(1, owner.getObjectId()); binding.setInt(2, body.getObjectId());
						try (var rows = binding.executeQuery()) { if (!rows.next()) throw new SQLException("Ownership changed"); }
					}
					try (PreparedStatement prior = con.prepareStatement("SELECT companion_player_id,item_unique_id,to_companion FROM ai_companion_item_transfers WHERE owner_player_id=? AND request_id=? FOR UPDATE")) {
						prior.setInt(1, owner.getObjectId()); prior.setString(2, request.id().toString());
						try (var rows = prior.executeQuery()) {
							if (rows.next()) {
								if (rows.getInt(1) != body.getObjectId() || rows.getInt(2) != item.getObjectId() || rows.getBoolean(3) != request.toBody())
									throw new SQLException("Receipt reused for another intent");
								con.rollback(); return;
							}
						}
					}
					if (!CompanionItemPolicy.transferable(item) || source.getInventory().getItemByObjId(item.getObjectId()) != item
						|| item.getItemCount() != request.count() || target.getInventory().getFreeSlots() < 1)
						throw new SQLException("Item changed, became nontradeable, or target is full");
					verifyInventory(con, owner); verifyInventory(con, body);
					verifyItem(con, item);
					try (PreparedStatement update = con.prepareStatement("""
						UPDATE inventory SET item_owner=?,slot=65535 WHERE item_unique_id=? AND item_owner=? AND item_count=?
						AND item_location=0 AND is_equipped=0 AND is_soul_bound=0 AND expire_time=0 AND pack_count=0
						""")) {
						update.setInt(1, target.getObjectId()); update.setInt(2, item.getObjectId()); update.setInt(3, source.getObjectId()); update.setLong(4, request.count());
						if (update.executeUpdate() != 1) throw new SQLException("Native item preconditions changed");
					}
					try (PreparedStatement receipt = con.prepareStatement("INSERT INTO ai_companion_item_transfers (owner_player_id,request_id,companion_player_id,item_unique_id,to_companion) VALUES (?,?,?,?,?)")) {
						receipt.setInt(1, owner.getObjectId()); receipt.setString(2, request.id().toString()); receipt.setInt(3, body.getObjectId());
						receipt.setInt(4, item.getObjectId()); receipt.setBoolean(5, request.toBody()); receipt.executeUpdate();
					}
					// The old detached Item must never be written by an already-captured native autosave batch.
					// Keep this fence even after an ambiguous commit error. A fresh login loads a new object.
					synchronized (retired) { retired.add(new java.lang.ref.WeakReference<>(item)); }
					con.commit();
				} catch (SQLException | RuntimeException failure) {
					try { con.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
					throw failure;
				}
			} catch (SQLException e) { throw new IllegalStateException("Transfer not confirmed; inspect its receipt before retrying", e); }
		} finally { fence.unlock(); }
	}

	private static void verifyInventory(Connection con, Player player) throws SQLException {
		Map<Integer, String> actual = new TreeMap<>(), expected = new TreeMap<>();
		var items = new ArrayList<>(player.getInventory().getItemsWithKinah());
		items.addAll(player.getEquipment().getEquippedItems());
		for (Item item : items) actual.put(item.getObjectId(), item.getItemId() + ":" + item.getItemCount() + ":" + item.isEquipped());
		try (PreparedStatement statement = con.prepareStatement("SELECT item_unique_id,item_id,item_count,is_equipped FROM inventory WHERE item_owner=? AND item_location=0 FOR UPDATE")) {
			statement.setInt(1, player.getObjectId());
			try (var rows = statement.executeQuery()) {
				while (rows.next()) expected.put(rows.getInt(1), rows.getInt(2) + ":" + rows.getLong(3) + ":" + rows.getBoolean(4));
			}
		}
		if (!expected.equals(actual)) throw new SQLException("Native inventory save incomplete; transfer refused");
	}

	private static void verifyItem(Connection con, Item item) throws SQLException {
		String[] columns = ("item_id,item_count,item_color,color_expires,item_creator,expire_time,activation_count,is_equipped,is_soul_bound,slot,"
			+ "item_location,enchant,enchant_bonus,item_skin,fusioned_item,optional_socket,optional_fusion_socket,charge,tune_count,rnd_bonus,"
			+ "fusion_rnd_bonus,tempering,pack_count,is_amplified,buff_skill,rnd_plume_bonus,rank_limit_expire_time").split(",");
		Object[] values = { item.getItemId(), item.getItemCount(), item.getItemColor(), item.getColorExpireTime(), item.getItemCreator(),
			item.getExpireTime(), item.getActivationCount(), item.isEquipped() ? 1 : 0, item.isSoulBound() ? 1 : 0, item.getEquipmentSlot(),
			item.getItemLocation(), item.getEnchantLevel(), item.getEnchantBonus(), item.getItemSkinTemplate().getTemplateId(), item.getFusionedItemId(),
			item.getOptionalSockets(), item.getFusionedItemOptionalSockets(), item.getChargePoints(), item.getTuneCount(), item.getBonusStatsId(),
			item.getFusionedItemBonusStatsId(), item.getTempering(), item.getPackCount(), item.isAmplified() ? 1 : 0, item.getBuffSkill(),
			item.getRndPlumeBonusValue(), item.getRankLimitExpireTime() };
		try (PreparedStatement statement = con.prepareStatement("SELECT * FROM inventory WHERE item_unique_id=? FOR UPDATE")) {
			statement.setInt(1, item.getObjectId());
			try (var rows = statement.executeQuery()) {
				if (!rows.next()) throw new SQLException("Missing transfer item");
				for (int i = 0; i < columns.length; i++) {
					Object actual = rows.getObject(columns[i]);
					if (actual instanceof Boolean flag) actual = flag ? 1 : 0;
					boolean equal = actual instanceof Number number && values[i] instanceof Number expected
						? number.longValue() == expected.longValue() : Objects.equals(actual, values[i]);
					if (!equal) throw new SQLException("Item checkpoint incomplete: " + columns[i]);
				}
			}
		}
	}
}
