package com.aionemu.gameserver.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.gameserver.model.gameobjects.Item;
import com.aionemu.gameserver.model.gameobjects.Persistable.PersistentState;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.items.storage.StorageType;
import com.aionemu.gameserver.services.ai.persistence.CompanionPersistenceException;

/** Frozen, character-scoped writes. No account storage, passports, rewards or online markers. */
public final class CompanionBodyDAO {

	private CompanionBodyDAO() { }

	public record Write(String sql, List<Object> parameters) {
		public Write { parameters = java.util.Collections.unmodifiableList(new ArrayList<>(parameters)); }
		void execute(Connection connection) throws SQLException {
			try (PreparedStatement statement = connection.prepareStatement(sql)) {
				for (int i = 0; i < parameters.size(); i++) statement.setObject(i + 1, parameters.get(i));
				statement.executeUpdate();
			}
		}
	}

	public record ItemIdentity(int id, int templateId, boolean newItem, boolean deleted) { }
	public record Snapshot(int ownerId, int bodyId, int accountId, String accountName, String databaseName,
			List<ItemIdentity> items, List<Write> writes) {
		public Snapshot { items = List.copyOf(items); writes = List.copyOf(writes); }
	}

	private static Write write(String sql, Object... values) { return new Write(sql, Arrays.asList(values)); }

	/** Native loaders log some SQL errors; refuse a writable session if core collections were only partially loaded. */
	public static void verifyLoaded(Player player) {
		try (Connection connection = DatabaseFactory.getConnection()) {
			Map<Integer, Integer> expectedSkills = new TreeMap<>(), actualSkills = new TreeMap<>();
			try (PreparedStatement statement = connection.prepareStatement(PlayerSkillListDAO.SELECT_QUERY)) {
				statement.setInt(1, player.getObjectId());
				try (var rows = statement.executeQuery()) {
					while (rows.next()) expectedSkills.put(rows.getInt(1), rows.getInt(2));
				}
			}
			player.getSkillList().getAllSkills().forEach(s -> actualSkills.put(s.getSkillId(), s.getSkillLevel()));
			Map<Integer, String> expectedItems = new TreeMap<>(), actualItems = new TreeMap<>();
			try (PreparedStatement statement = connection.prepareStatement(
				"SELECT item_unique_id,item_id,item_count FROM inventory WHERE item_owner=? AND item_location IN (0,1)")) {
				statement.setInt(1, player.getObjectId());
				try (var rows = statement.executeQuery()) {
					while (rows.next()) expectedItems.put(rows.getInt(1), rows.getInt(2) + ":" + rows.getLong(3));
				}
			}
			List<Item> items = new ArrayList<>(player.getInventory().getItemsWithKinah());
			items.addAll(player.getEquipment().getEquippedItems()); items.addAll(player.getWarehouse().getItemsWithKinah());
			items.forEach(i -> actualItems.put(i.getObjectId(), i.getItemId() + ":" + i.getItemCount()));
			if (!expectedSkills.equals(actualSkills) || !expectedItems.equals(actualItems))
				throw new SQLException("Body skills or inventory did not load completely");
		} catch (SQLException e) {
			throw new CompanionPersistenceException("Companion body load could not be verified", e);
		}
	}

	/** Caller has stopped actions and removed the actor from the world before freezing it. */
	public static Snapshot snapshot(int ownerId, Player player) {
		if (player.getClientConnection() != null || player.isInWorld() || player.isSpawned())
			throw new IllegalStateException("Body must be disconnected and quiescent before checkpoint");
		int id = player.getObjectId();
		var common = player.getCommonData();
		List<Write> writes = new ArrayList<>();
		writes.add(write("""
			UPDATE players SET exp=?, recoverexp=?, player_class=?, quest_expands=?, npc_expands=?, item_expands=?,
			wh_npc_expands=?, wh_bonus_expands=?, dp=?, soul_sickness=?, reposte_energy=?, pvp_exp=? WHERE id=?
			""", common.getExp(), common.getExpRecoverable(), common.getPlayerClass().name(), common.getQuestExpands(),
			common.getNpcExpands(), common.getItemExpands(), common.getWhNpcExpands(), common.getWhBonusExpands(), common.getDp(),
			common.getDeathCount(), common.getCurrentReposeEnergy(), common.getPvpExp(), id));
		// Spawn position is owned by summon; never persist a temporary instance or owner-relative position.
		writes.add(write("DELETE FROM player_skills WHERE player_id=?", id));
		player.getSkillList().getAllSkills().stream().filter(s -> s.getPersistentState() != PersistentState.NOACTION
			&& s.getPersistentState() != PersistentState.DELETED).forEach(s ->
			writes.add(write(PlayerSkillListDAO.INSERT_QUERY, id, s.getSkillId(), s.getSkillLevel())));
		writes.add(write(PlayerCooldownsDAO.DELETE_QUERY, id));
		long now = System.currentTimeMillis();
		if (player.getSkillCoolDowns() != null) player.getSkillCoolDowns().forEach((key, until) -> {
			if (until != null && until > now) writes.add(write(PlayerCooldownsDAO.INSERT_QUERY, id, key, until));
		});
		writes.add(write(ItemCooldownsDAO.DELETE_QUERY, id));
		player.getItemCoolDowns().forEach((key, value) -> {
			if (value != null && value.getReuseTime() > now)
				writes.add(write(ItemCooldownsDAO.INSERT_QUERY, id, key, value.getUseDelay(), value.getReuseTime()));
		});
		writes.add(write("""
			INSERT INTO player_life_stats (player_id,hp,mp,fp) VALUES (?,?,?,?)
			ON DUPLICATE KEY UPDATE hp=VALUES(hp),mp=VALUES(mp),fp=VALUES(fp)
			""", id, player.getLifeStats().getCurrentHp(), player.getLifeStats().getCurrentMp(), player.getLifeStats().getCurrentFp()));
		writes.add(write(PlayerEffectsDAO.DELETE_QUERY, id));
		for (var effect : player.getEffectController().getAbnormalEffects()) {
			if (!effect.canSaveOnLogout() || effect.getRemainingTimeMillis() <= 0) continue;
			int criticals = 0;
			for (var template : effect.getEffectTemplates())
				if (effect.isMagicalCritical(template.getPosition())) criticals |= 1 << (template.getPosition() - 1);
			writes.add(write(PlayerEffectsDAO.INSERT_QUERY, id, effect.getSkillId(), effect.getSkillLevel(),
				(int) effect.getRemainingTimeMillis(), effect.getEndTime(),
				effect.getForceType() == null ? null : effect.getForceType().getName(), criticals));
		}
		Map<Integer, Item> items = new TreeMap<>();
		List<Item> candidates = new ArrayList<>(player.getInventory().getItemsWithKinah());
		candidates.addAll(player.getEquipment().getEquippedItems());
		candidates.addAll(player.getWarehouse().getItemsWithKinah());
		candidates.addAll(player.getInventory().getDeletedItems());
		candidates.addAll(player.getWarehouse().getDeletedItems());
		for (Item item : candidates) {
			Item prior = items.putIfAbsent(item.getObjectId(), item);
			if (prior != null && prior != item) throw new IllegalStateException("Duplicate item identity in body");
		}
		List<ItemIdentity> identities = new ArrayList<>();
		for (Item item : items.values()) {
			if (item.getItemLocation() != StorageType.CUBE.getId() && item.getItemLocation() != StorageType.REGULAR_WAREHOUSE.getId())
				throw new IllegalStateException("Shared or unsupported storage cannot be checkpointed");
			if (item.getPersistentState() == PersistentState.NOACTION) continue;
			boolean deleted = item.getPersistentState() == PersistentState.DELETED;
			identities.add(new ItemIdentity(item.getObjectId(), item.getItemId(), item.getPersistentState() == PersistentState.NEW, deleted));
			if (deleted) {
				writes.add(write("DELETE FROM inventory WHERE item_unique_id=? AND item_owner=? AND item_location IN (0,1)", item.getObjectId(), id));
				continue;
			}
			// Use the native schema and serialization fields; frozen values make a retry identical.
			writes.add(write(InventoryDAO.INSERT_QUERY + " ON DUPLICATE KEY UPDATE "
				+ "item_count=VALUES(item_count),item_color=VALUES(item_color),color_expires=VALUES(color_expires),"
				+ "item_creator=VALUES(item_creator),expire_time=VALUES(expire_time),activation_count=VALUES(activation_count),"
				+ "is_equipped=VALUES(is_equipped),is_soul_bound=VALUES(is_soul_bound),slot=VALUES(slot),item_location=VALUES(item_location),"
				+ "enchant=VALUES(enchant),enchant_bonus=VALUES(enchant_bonus),item_skin=VALUES(item_skin),fusioned_item=VALUES(fusioned_item),"
				+ "optional_socket=VALUES(optional_socket),optional_fusion_socket=VALUES(optional_fusion_socket),charge=VALUES(charge),"
				+ "tune_count=VALUES(tune_count),rnd_bonus=VALUES(rnd_bonus),fusion_rnd_bonus=VALUES(fusion_rnd_bonus),tempering=VALUES(tempering),"
				+ "pack_count=VALUES(pack_count),is_amplified=VALUES(is_amplified),buff_skill=VALUES(buff_skill),"
				+ "rnd_plume_bonus=VALUES(rnd_plume_bonus),rank_limit_expire_time=VALUES(rank_limit_expire_time)",
				item.getObjectId(), item.getItemId(), item.getItemCount(), item.getItemColor(), item.getColorExpireTime(), item.getItemCreator(),
				item.getExpireTime(), item.getActivationCount(), id, item.isEquipped(), item.isSoulBound(), item.getEquipmentSlot(),
				item.getItemLocation(), item.getEnchantLevel(), item.getEnchantBonus(), item.getItemSkinTemplate().getTemplateId(),
				item.getFusionedItemId(), item.getOptionalSockets(), item.getFusionedItemOptionalSockets(), item.getChargePoints(),
				item.getTuneCount(), item.getBonusStatsId(), item.getFusionedItemBonusStatsId(), item.getTempering(), item.getPackCount(),
				item.isAmplified(), item.getBuffSkill(), item.getRndPlumeBonusValue(), item.getRankLimitExpireTime()));
		}
		return new Snapshot(ownerId, id, player.getAccount().getId(), player.getAccountName(), common.getName(), identities, writes);
	}

	public static void save(Snapshot snapshot) {
		try (Connection connection = DatabaseFactory.getConnection()) {
			save(connection, snapshot);
		} catch (SQLException e) {
			throw new CompanionPersistenceException("Companion body checkpoint failed for " + snapshot.bodyId(), e);
		}
	}

	static void save(Connection connection, Snapshot snapshot) throws SQLException {
		connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
		connection.setAutoCommit(false);
		try {
			try (PreparedStatement statement = connection.prepareStatement("""
				SELECT p.id FROM ai_companion_creation_claims claim
				JOIN ai_companions binding ON binding.owner_player_id=claim.owner_player_id
				JOIN players p ON p.id=claim.companion_player_id
				WHERE claim.owner_player_id=? AND p.id=? AND p.account_id=? AND p.account_name=? AND p.name=?
				AND claim.host_account_id=p.account_id AND claim.host_account_name=p.account_name AND claim.body_name=p.name
				AND binding.companion_player_id=p.id AND binding.role='PERSONAL_COMPANION' AND p.online=0 FOR UPDATE
				""")) {
				statement.setInt(1, snapshot.ownerId()); statement.setInt(2, snapshot.bodyId()); statement.setInt(3, snapshot.accountId());
				statement.setString(4, snapshot.accountName()); statement.setString(5, snapshot.databaseName());
				try (var rows = statement.executeQuery()) {
					if (!rows.next() || rows.next()) throw new SQLException("Immutable body ownership changed; checkpoint refused");
				}
			}
			for (ItemIdentity item : snapshot.items()) {
				try (PreparedStatement statement = connection.prepareStatement(
					"SELECT item_owner,item_id,item_location FROM inventory WHERE item_unique_id=? FOR UPDATE")) {
					statement.setInt(1, item.id());
					try (var rows = statement.executeQuery()) {
						if (rows.next()) {
							if (rows.getInt(1) != snapshot.bodyId() || rows.getInt(2) != item.templateId()
								|| rows.getInt(3) < 0 || rows.getInt(3) > 1) throw new SQLException("Item ownership changed");
						} else if (!item.newItem() && !item.deleted()) throw new SQLException("Existing item is missing; refusing to recreate it");
					}
				}
			}
			for (Write write : snapshot.writes()) write.execute(connection);
			connection.commit();
		} catch (SQLException | RuntimeException failure) {
			try { connection.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
			throw failure;
		}
	}
}
