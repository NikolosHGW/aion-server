package com.aionemu.gameserver.services.ai.creation;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

import com.alibaba.fastjson2.JSON;
import com.aionemu.gameserver.model.Gender;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.Race;
import com.aionemu.gameserver.model.gameobjects.player.PlayerAppearance;

/** Immutable creation-time values. Intentionally has no source character ID or item/progression fields. */
public record CompanionBlueprint(String race, String gender, String playerClass, String nickname, String appearanceJson) {

	public static final String COMPANION_PREFIX = "[AI:C]";
	/** Reserved for the future independent citizen role, not enabled by C3. */
	public static final String CITIZEN_PREFIX = "[AI:P]";

	public CompanionBlueprint {
		if (!Race.ELYOS.name().equals(race) && !Race.ASMODIANS.name().equals(race))
			throw new IllegalArgumentException("Unsupported companion race");
		Gender.valueOf(gender);
		if (PlayerClass.valueOf(playerClass).isStartingClass())
			throw new IllegalArgumentException("Select the companion's permanent advanced class");
		if (nickname == null || !nickname.matches("[A-Za-zА-Яа-яЁё]{2,10}"))
			throw new IllegalArgumentException("Companion nickname must contain 2-10 Latin or Cyrillic letters; the AI prefix is added automatically");
		CompanionAppearanceSnapshot.decode(appearanceJson);
	}

	public PlayerAppearance appearance() { return CompanionAppearanceSnapshot.decode(appearanceJson); }
	public PlayerClass advancedClass() { return PlayerClass.valueOf(playerClass); }
	public PlayerClass classAt(int level) { return level < 10 ? advancedClass().getStartingClass() : advancedClass(); }
	public String displayName() { return COMPANION_PREFIX + nickname; }
	public String encode() { return JSON.toJSONString(this); }
	public static CompanionBlueprint decode(String value) {
		if (value == null) return null;
		if (value.length() > 16384) throw new IllegalArgumentException("Invalid companion blueprint");
		var object = JSON.parseObject(value);
		if (object == null || !object.keySet().equals(Set.of("race", "gender", "playerClass", "nickname", "appearanceJson")))
			throw new IllegalArgumentException("Unknown companion blueprint schema");
		return new CompanionBlueprint(object.getString("race"), object.getString("gender"), object.getString("playerClass"),
			object.getString("nickname"), object.getString("appearanceJson"));
	}

	public static PlayerClass selectClass(PlayerClass source, String specialization) {
		if (!source.isStartingClass()) {
			if (specialization != null && !source.name().equalsIgnoreCase(specialization))
				throw new IllegalArgumentException("An advanced source class is copied unchanged: " + source);
			return source;
		}
		if (specialization != null) {
			PlayerClass selected = PlayerClass.valueOf(specialization.toUpperCase(Locale.ROOT));
			if (!selected.isStartingClass() && selected.getStartingClass() == source) return selected;
		}
		throw new IllegalArgumentException("Choose a specialization for " + source + ": "
			+ Arrays.stream(PlayerClass.values()).filter(c -> !c.isStartingClass() && c.getStartingClass() == source).toList());
	}
}
