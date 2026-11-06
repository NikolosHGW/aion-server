package com.aionemu.gameserver.services.ai;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.gameserver.GameServer;
import com.aionemu.gameserver.dao.*;
import com.aionemu.gameserver.dataholders.DataManager;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.AccountService;
import com.aionemu.gameserver.services.ai.creation.*;
import com.aionemu.gameserver.services.ai.progression.CompanionProgression;
import com.aionemu.gameserver.services.player.PlayerService;

/** Explicit native integration test against a fresh disposable DB. No client, connection or game login is fabricated. */
public final class CompanionC3RuntimeProbe {
	private static final int HOST = 880003;
	private static final String HOST_NAME = "ai_c3_host";

	static void check(boolean condition, String detail) {
		if (!condition) throw new AssertionError(detail);
		System.out.println("C3_CHECK PASS " + detail);
	}
	static void rejected(Runnable action, String detail) {
		try { action.run(); } catch (RuntimeException expected) { check(true, detail); return; }
		throw new AssertionError("Expected rejection: " + detail);
	}
	public static void main(String[] args) throws Exception {
		Properties config = new Properties();
		try (var in = Files.newInputStream(Path.of("config/mygs.properties"))) { config.load(in); }
		if (!config.getProperty("database.url", "").startsWith("jdbc:mysql://127.0.0.1:53411/ai_c3_runtime?"))
			throw new IllegalStateException("Disposable C3 database guard failed");
		if (args.length != 1 || !List.of("create", "restore").contains(args[0])) throw new IllegalArgumentException();
		try {
			var main = GameServer.class.getDeclaredMethod("main"); main.setAccessible(true); main.invoke(null);
			run(args[0]);
			System.out.println("C3_RUNTIME_PASS phase=" + args[0]);
			GameServer.initShutdown(0, 0);
		} catch (Throwable failure) {
			failure.printStackTrace(); System.err.println("C3_RUNTIME_FAIL"); GameServer.initShutdown(1, 0);
		}
	}

	static Player load(int account, String accountName, int id, boolean verifyBody) {
		Player result = PlayerService.getServerControlledPlayer(id, AccountService.loadServerControlledAccount(account, accountName, id));
		if (verifyBody) CompanionBodyDAO.verifyLoaded(result);
		return result;
	}
	static long scalar(String query) throws Exception {
		try (var connection = DatabaseFactory.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
			if (!rows.next()) throw new AssertionError("Missing fixture row"); return rows.getLong(1);
		}
	}
	static void fixture(String sql) throws Exception {
		try (var connection = DatabaseFactory.getConnection(); var statement = connection.createStatement()) { statement.executeUpdate(sql); }
	}
	static void close(Player player) {
		player.getEffectController().removeAllEffects(true); player.getLifeStats().cancelAllTasks();
	}

	static void run(String phase) throws Exception {
		var gateway = new DefaultCompanionPlayerCreationGateway();
		var repository = new CompanionPersistenceDAO();
		var creation = new CompanionCreationService(repository, gateway);
		var sources = new CompanionSourceDAO();
		check(CompanionProgression.ordinarySkills(PlayerClass.GLADIATOR, 10, com.aionemu.gameserver.model.Race.ELYOS).containsKey(44),
			"gladiator learns native advanced-class level-9 proficiency after ascension");
		if (phase.equals("create")) {
			gateway.create(new CompanionCreationRequest(770031, "ELYOS", "MALE"), "Cthreeownera", 770031, "c3_owner_a");
			gateway.create(new CompanionCreationRequest(770032, "ELYOS", "FEMALE"), "Cthreeownerb", 770032, "c3_owner_b");
			var source = gateway.create(new CompanionCreationRequest(770031, "ELYOS", "FEMALE"), "Vasya", 770031, "c3_owner_a");
			// Deliberately rich, high-level source fixture. Only its appearance/class may enter the blueprint.
			fixture("UPDATE players SET player_class='CLERIC', exp=" + DataManager.PLAYER_EXPERIENCE_TABLE.getStartExpForLevel(65)
				+ " WHERE id=" + source.playerId());
			var appearance = PlayerAppearanceDAO.load(source.playerId());
			appearance.setHair(19); appearance.setFace(12); appearance.setVoice(4); appearance.setHeightScale(1.1f);
			check(PlayerAppearanceDAO.store(source.playerId(), appearance), "source appearance fixture stored");
			fixture("INSERT INTO inventory(item_unique_id,item_id,item_count,item_owner,item_location,item_skin) VALUES(990003,182400001,987654,"
				+ source.playerId() + ",0,182400001)");
			check(sources.list(770031).size() == 2 && sources.list(770032).size() == 1, "account scoped template lists");
			rejected(() -> sources.snapshot(770032, "Vasya", null, null, "ELYOS"), "foreign template rejected");
			rejected(() -> sources.snapshot(770031, "Vasya", null, null, "ASMODIANS"), "cross faction template rejected");
			var blueprint = sources.snapshot(770031, "Vasya", null, null, "ELYOS");
			int ownerId = (int) scalar("SELECT id FROM players WHERE name='Cthreeownera'");
			int otherId = (int) scalar("SELECT id FROM players WHERE name='Cthreeownerb'");
			var created = creation.create(new CompanionCreationRequest(ownerId, "ELYOS", "FEMALE", blueprint), HOST, HOST_NAME);
			var otherBlueprint = sources.snapshot(770032, "Cthreeownerb", "Boris", "gladiator", "ELYOS");
			creation.create(new CompanionCreationRequest(otherId, "ELYOS", "FEMALE", otherBlueprint), HOST, HOST_NAME);
			check(repository.loadCreationClaim(ownerId).orElseThrow().blueprint().equals(blueprint), "immutable blueprint SQL roundtrip");
			check(scalar("SELECT COUNT(*) FROM inventory WHERE item_owner=" + created.body().playerId() + " AND item_unique_id=990003") == 0,
				"source item instance not copied");
			long starterKinah = DataManager.PLAYER_INITIAL_DATA.getPlayerCreationData(PlayerClass.PRIEST).getItems().stream()
				.filter(item -> item.getTemplate().getTemplateId() == 182400001).mapToLong(item -> item.getCount()).sum();
			check(scalar("SELECT COALESCE(SUM(item_count),0) FROM inventory WHERE item_owner=" + created.body().playerId() + " AND item_id=182400001") == starterKinah,
				"only native starter kinah, no source kinah copied");
			var aliasCollision = new CompanionCreationClaim(source.playerId(), CompanionCreationPolicy.databaseName(source.playerId()), HOST, HOST_NAME, null, blueprint);
			rejected(() -> repository.reserveCreation(aliasCollision), "permanent alias collision rejected");
			fixture("UPDATE player_appearance SET hair=1 WHERE player_id=" + source.playerId());
			PlayerService.deletePlayerFromDB(source.playerId());
			check(scalar("SELECT COUNT(*) FROM inventory WHERE item_owner=" + source.playerId()) == 0,
				"native source deletion leaves no orphan items for a reused player ID");
			check(repository.loadCreationClaim(ownerId).orElseThrow().blueprint().appearance().getHair() == 19, "source edits and deletion cannot change companion");
		}

		int ownerId = (int) scalar("SELECT id FROM players WHERE name='Cthreeownera'");
		int otherId = (int) scalar("SELECT id FROM players WHERE name='Cthreeownerb'");
		var first = creation.create(new CompanionCreationRequest(ownerId, "ELYOS", "MALE"), HOST, HOST_NAME);
		check(first.status() == CompanionCreationResult.Status.ALREADY_EXISTS, "repeat create needs no source and keeps identity");
		var claim = repository.loadCreationClaim(ownerId).orElseThrow();
		var otherClaim = repository.loadCreationClaim(otherId).orElseThrow();
		Player owner = load(770031, "c3_owner_a", ownerId, false);
		Player body = load(HOST, HOST_NAME, first.body().playerId(), true);
		Player other = load(HOST, HOST_NAME, otherClaim.companionPlayerId(), true);
		check(CompanionAppearanceSnapshot.encode(body.getPlayerAppearance()).equals(claim.blueprint().appearanceJson()), "native appearance restored independently");
		body.setServerControlledDisplayName(claim.blueprint().displayName());
		check(body.getName().equals("[AI:C]Vasya") && body.getCommonData().getName().equals(claim.databaseName()), "immutable AI label separate from DB identity");
		long initialItems = scalar("SELECT COUNT(*) FROM inventory WHERE item_owner=" + body.getObjectId());
		long initialQuests = scalar("SELECT COUNT(*) FROM player_quests WHERE player_id=" + body.getObjectId());
		var progression = new CompanionProgression(owner, body, claim, () -> true); // Unit authorization seam; production supplies current owner lease + flags.
		rejected(() -> new CompanionProgression(owner, other, claim, () -> true).synchronize(), "other owner's body rejected");
		rejected(() -> new CompanionProgression(owner, body, claim, () -> false).synchronize(), "disabled/stale authorization rejected");

		if (phase.equals("create")) {
			check(body.getLevel() == 1 && body.getPlayerClass() == PlayerClass.PRIEST, "source level 65 is not copied");
			for (int level : new int[]{1, 9, 10, 65, 10, 1, 10}) {
				owner.getCommonData().synchronizeCompanionProfile(owner, level, level < 10 ? PlayerClass.WARRIOR : PlayerClass.GLADIATOR);
				progression.synchronize();
				check(body.getLevel() == level && body.getPlayerClass() == (level < 10 ? PlayerClass.PRIEST : PlayerClass.CLERIC), "native profile at level " + level);
				check(body.getSkillList().isSkillPresent(89) == (level >= 10), "cleric native advanced-class proficiency follows promotion and downgrade");
				check(body.getCommonData().getNoExp() && body.getSkillList().getAllSkills().stream().noneMatch(s -> s.isStigmaSkill()), "no independent XP or automatic stigma at " + level);
			}
			long cooldown = System.currentTimeMillis() + 3_600_000;
			body.setSkillCoolDown(12345, cooldown);
			body.getLifeStats().setCurrentHp(Math.max(1, body.getLifeStats().getMaxHp() / 3));
			body.getLifeStats().setCurrentMp(Math.max(1, body.getLifeStats().getMaxMp() / 4));
			int hp = body.getLifeStats().getCurrentHp(), mp = body.getLifeStats().getCurrentMp();
			progression.synchronize(); progression.synchronize();
			check(body.getLifeStats().getCurrentHp() == hp && body.getLifeStats().getCurrentMp() == mp, "repeat synchronization does not heal");
			check(body.getSkillCoolDowns().get(12345) == cooldown, "synchronization does not reset cooldowns");
			CompanionBodyDAO.save(CompanionBodyDAO.snapshot(ownerId, body));
			fixture("UPDATE players SET player_class='GLADIATOR', exp=" + (DataManager.PLAYER_EXPERIENCE_TABLE.getStartExpForLevel(10) + 1)
				+ " WHERE id=" + ownerId);
		} else {
			check(owner.getLevel() == 10, "owner restored at level 10");
			progression.synchronize();
			check(body.getLevel() == 10 && body.getPlayerClass() == PlayerClass.CLERIC, "exact level 10 survives restart without fake ascension quest");
			check(body.getSkillList().isSkillPresent(89), "cleric advanced-class proficiency survives restart");
			check(body.getLifeStats().getCurrentHp() < body.getLifeStats().getMaxHp() && body.getLifeStats().getCurrentMp() < body.getLifeStats().getMaxMp(),
				"restart synchronization preserves injured state");
			check(body.getSkillCoolDowns().containsKey(12345), "cooldown survives restart");
			CompanionBodyDAO.save(CompanionBodyDAO.snapshot(ownerId, body));
		}
		check(scalar("SELECT COUNT(*) FROM inventory WHERE item_owner=" + body.getObjectId()) == initialItems, "progression creates no reward items");
		check(scalar("SELECT COUNT(*) FROM player_quests WHERE player_id=" + body.getObjectId()) == initialQuests, "progression creates no quest journal entries");
		check(other.getLevel() == 1 && other.getPlayerClass() == PlayerClass.WARRIOR, "other companion unchanged");
		check(scalar("SELECT COUNT(*) FROM players WHERE account_id=" + HOST) == 2, "two owners keep exactly two companion bodies");
		for (Player actor : List.of(owner, body, other)) close(actor);
	}
}
