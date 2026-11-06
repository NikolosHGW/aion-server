package com.aionemu.gameserver.services.ai;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.gameserver.GameServer;
import com.aionemu.gameserver.dao.CompanionBodyDAO;
import com.aionemu.gameserver.dao.CompanionPersistenceDAO;
import com.aionemu.gameserver.model.account.Account;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.AccountService;
import com.aionemu.gameserver.services.ai.creation.*;
import com.aionemu.gameserver.services.ai.persistence.CompanionPersistenceException;
import com.aionemu.gameserver.services.player.PlayerService;

/** Explicit disposable-server integration probe; never connects or impersonates a game client. */
public final class CompanionC2RuntimeProbe {
	static void check(boolean value, String detail) {
		if (!value) throw new AssertionError(detail);
		System.out.println("C2_CHECK PASS " + detail);
	}
	public static void main(String[] args) throws Exception {
		Properties config = new Properties();
		try (var stream = Files.newInputStream(Path.of("config/mygs.properties"))) { config.load(stream); }
		if (!config.getProperty("database.url", "").startsWith("jdbc:mysql://127.0.0.1:53410/ai_c2_runtime?"))
			throw new IllegalStateException("Disposable C2 database guard failed");
		if (args.length != 1 || !List.of("create", "restore").contains(args[0])) throw new IllegalArgumentException();
		try {
			var main = GameServer.class.getDeclaredMethod("main"); main.setAccessible(true); main.invoke(null);
			run(args[0]);
			System.out.println("C2_RUNTIME_PASS phase=" + args[0]);
			GameServer.initShutdown(0, 0);
		} catch (Throwable failure) {
			failure.printStackTrace(); System.err.println("C2_RUNTIME_FAIL"); GameServer.initShutdown(1, 0);
		}
	}
	static Player load(int id) {
		Account account = AccountService.loadServerControlledAccount(880002, "ai_c2_host", id);
		Player body = PlayerService.getServerControlledPlayer(id, account);
		CompanionBodyDAO.verifyLoaded(body);
		return body;
	}
	static void run(String phase) throws Exception {
		var creation = new CompanionCreationService(new CompanionPersistenceDAO(), new DefaultCompanionPlayerCreationGateway());
		if (phase.equals("create")) {
			var gateway = new DefaultCompanionPlayerCreationGateway();
			gateway.create(new CompanionCreationRequest(770011, "ELYOS", "MALE"), "Ctwoownera", 770011, "c2_owner_a");
			gateway.create(new CompanionCreationRequest(770012, "ELYOS", "FEMALE"), "Ctwoownerb", 770012, "c2_owner_b");
		}
		List<Integer> owners = new ArrayList<>();
		try (var connection = DatabaseFactory.getConnection(); var statement = connection.createStatement();
			var rows = statement.executeQuery("SELECT id FROM players WHERE name IN ('Ctwoownera','Ctwoownerb') ORDER BY name")) {
			while (rows.next()) owners.add(rows.getInt(1));
		}
		check(owners.size() == 2, "two independent owners");
		var first = creation.create(new CompanionCreationRequest(owners.get(0), "ELYOS", "MALE"), 880002, "ai_c2_host");
		var second = creation.create(new CompanionCreationRequest(owners.get(1), "ELYOS", "FEMALE"), 880002, "ai_c2_host");
		Player body = load(first.body().playerId()); Player other = load(second.body().playerId());
		Path expectations = Path.of("c2-expectations.properties"); Properties expected = new Properties();
		if (phase.equals("create")) {
			body.setServerControlledDisplayName("[AI]" + first.body().databaseName());
			check(body.getName().startsWith("[AI]") && body.getCommonData().getName().equals(first.body().databaseName()),
				"display name does not mutate database identity");
			var skill = body.getSkillList().getAllSkills().getFirst();
			// Native state fixture changes test persistence, not a product progression or reward API.
			skill.setSkillLvl(skill.getSkillLevel() + 1);
			body.getLifeStats().setCurrentHp(Math.max(1, body.getLifeStats().getCurrentHp() - 17));
			body.getLifeStats().setCurrentMp(Math.max(1, body.getLifeStats().getCurrentMp() - 11));
			long until = System.currentTimeMillis() + 600_000;
			body.setSkillCoolDown(12345, until); body.addItemCoolDown(23456, until, 60);
			var stack = body.getInventory().getItems().stream().filter(i -> i.getItemCount() > 1).findFirst().orElseThrow();
			check(body.getInventory().decreaseByObjectId(stack.getObjectId(), 1), "native inventory consumption fixture");
			var snapshot = CompanionBodyDAO.snapshot(owners.getFirst(), body);
			var brokenWrites = new ArrayList<>(snapshot.writes());
			brokenWrites.add(new CompanionBodyDAO.Write("INSERT INTO c2_intentionally_missing_table VALUES (1)", List.of()));
			var broken = new CompanionBodyDAO.Snapshot(snapshot.ownerId(), snapshot.bodyId(), snapshot.accountId(), snapshot.accountName(),
				snapshot.databaseName(), snapshot.items(), brokenWrites);
			long before = scalar("SELECT SUM(skill_level) FROM player_skills WHERE player_id=" + body.getObjectId());
			try { CompanionBodyDAO.save(broken); throw new AssertionError("Fault accepted"); }
			catch (CompanionPersistenceException correct) { check(correct.getCause() instanceof SQLException, "SQL failure surfaced"); }
			check(scalar("SELECT SUM(skill_level) FROM player_skills WHERE player_id=" + body.getObjectId()) == before, "transaction rollback preserves skills");
			check(scalar("SELECT item_count FROM inventory WHERE item_unique_id=" + stack.getObjectId()) == stack.getItemCount() + 1,
				"transaction rollback preserves item consumption");
			var foreign = new CompanionBodyDAO.Snapshot(owners.get(1), snapshot.bodyId(), snapshot.accountId(), snapshot.accountName(),
				snapshot.databaseName(), snapshot.items(), snapshot.writes());
			try { CompanionBodyDAO.save(foreign); throw new AssertionError("Foreign owner accepted"); }
			catch (CompanionPersistenceException correct) { check(true, "foreign owner rejected"); }
			long otherSkills = scalar("SELECT SUM(skill_level) FROM player_skills WHERE player_id=" + other.getObjectId());
			CompanionBodyDAO.save(snapshot); CompanionBodyDAO.save(snapshot);
			check(scalar("SELECT SUM(skill_level) FROM player_skills WHERE player_id=" + other.getObjectId()) == otherSkills, "second body untouched");
			expected.setProperty("body", Integer.toString(body.getObjectId()));
			expected.setProperty("skill", Integer.toString(skill.getSkillId())); expected.setProperty("skillLevel", Integer.toString(skill.getSkillLevel()));
			expected.setProperty("item", Integer.toString(stack.getObjectId())); expected.setProperty("itemCount", Long.toString(stack.getItemCount()));
			expected.setProperty("hp", Integer.toString(body.getLifeStats().getCurrentHp())); expected.setProperty("mp", Integer.toString(body.getLifeStats().getCurrentMp()));
			expected.setProperty("cooldown", Long.toString(until));
			try (var output = Files.newOutputStream(expectations)) { expected.store(output, "C2 native roundtrip"); }
			body.getEffectController().removeAllEffects(true); body.getLifeStats().cancelAllTasks();
			body = load(body.getObjectId());
		} else {
			check(first.status() == CompanionCreationResult.Status.ALREADY_EXISTS, "restart keeps immutable ownership");
			try (var input = Files.newInputStream(expectations)) { expected.load(input); }
		}
		check(body.getObjectId() == Integer.parseInt(expected.getProperty("body")), "same persistent body");
		check(body.getName().equals(first.body().databaseName()), "runtime alias never persisted");
		check(body.getSkillList().getSkillLevel(Integer.parseInt(expected.getProperty("skill"))) == Integer.parseInt(expected.getProperty("skillLevel")), "skill changes survive reload");
		check(body.getInventory().getItemByObjId(Integer.parseInt(expected.getProperty("item"))).getItemCount() == Long.parseLong(expected.getProperty("itemCount")), "consumption persists exactly once");
		check(body.getLifeStats().getCurrentHp() == Integer.parseInt(expected.getProperty("hp")) && body.getLifeStats().getCurrentMp() == Integer.parseInt(expected.getProperty("mp")), "HP and MP persist");
		check(body.getSkillCoolDowns().get(12345) == Long.parseLong(expected.getProperty("cooldown"))
			&& body.getItemCoolDowns().get(23456).getReuseTime() == Long.parseLong(expected.getProperty("cooldown")), "native cooldowns persist");
		check(body.getAccount().getAccountWarehouse().getItemsWithKinah().isEmpty(), "AI account warehouse stays isolated");
		check(scalar("SELECT item_count FROM inventory WHERE item_unique_id=990001 AND item_owner=880002 AND item_location=2") == 17,
			"existing host warehouse item is neither loaded nor overwritten");
		check(scalar("SELECT COUNT(*) FROM players WHERE account_id=880002") == 2, "no duplicate bodies");
		for (Player player : List.of(body, other)) { player.getEffectController().removeAllEffects(true); player.getLifeStats().cancelAllTasks(); }
	}
	static long scalar(String sql) throws Exception {
		try (var con = DatabaseFactory.getConnection(); var statement = con.createStatement(); var rows = statement.executeQuery(sql)) {
			rows.next(); return rows.getLong(1);
		}
	}
}
