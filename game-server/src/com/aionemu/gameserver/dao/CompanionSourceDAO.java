package com.aionemu.gameserver.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.aionemu.commons.database.DatabaseFactory;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.services.ai.creation.CompanionAppearanceSnapshot;
import com.aionemu.gameserver.services.ai.creation.CompanionBlueprint;
import com.aionemu.gameserver.services.ai.persistence.CompanionPersistenceException;

/** Reads only the authenticated owner's account. Does not load source inventory, quests, skills or experience. */
public final class CompanionSourceDAO {

	public record Source(String name, String race, String gender, PlayerClass playerClass) {}

	public List<Source> list(int accountId) {
		requireAccount(accountId);
		try (Connection connection = DatabaseFactory.getConnection(); PreparedStatement statement = connection.prepareStatement(
			"SELECT name, race, gender, player_class FROM players WHERE account_id = ? AND deletion_date IS NULL ORDER BY name")) {
			statement.setInt(1, accountId);
			try (ResultSet result = statement.executeQuery()) {
				List<Source> sources = new ArrayList<>();
				while (result.next()) sources.add(new Source(result.getString("name"), result.getString("race"), result.getString("gender"),
					PlayerClass.valueOf(result.getString("player_class"))));
				return List.copyOf(sources);
			}
		} catch (SQLException e) {
			throw new CompanionPersistenceException("Could not list your companion templates", e);
		}
	}

	public CompanionBlueprint snapshot(int accountId, String sourceName, String nickname, String specialization, String ownerRace) {
		requireAccount(accountId);
		if (sourceName == null || sourceName.isBlank()) throw new IllegalArgumentException("Choose one of your characters with //companion sources");
		try (Connection connection = DatabaseFactory.getConnection(); PreparedStatement statement = connection.prepareStatement("""
			SELECT p.name, p.race, p.gender, p.player_class, a.*
			FROM players p JOIN player_appearance a ON a.player_id = p.id
			WHERE p.account_id = ? AND p.name = ? AND p.deletion_date IS NULL
			""")) {
			statement.setInt(1, accountId);
			statement.setString(2, sourceName);
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) throw new IllegalArgumentException("Template is unavailable in your account, pending deletion, or has no appearance");
				if (!ownerRace.equals(result.getString("race"))) throw new IllegalArgumentException("Choose a template of your own faction");
				CompanionBlueprint blueprint = new CompanionBlueprint(result.getString("race"), result.getString("gender"),
					CompanionBlueprint.selectClass(PlayerClass.valueOf(result.getString("player_class")), specialization).name(),
					nickname == null ? result.getString("name") : nickname,
					CompanionAppearanceSnapshot.encode(CompanionAppearanceSnapshot.read(result)));
				if (result.next()) throw new IllegalStateException("Ambiguous companion template");
				return blueprint;
			}
		} catch (SQLException e) {
			throw new CompanionPersistenceException("Could not snapshot your companion template", e);
		}
	}

	private static void requireAccount(int accountId) {
		if (accountId <= 0) throw new IllegalArgumentException("Authenticated account is required");
	}
}
