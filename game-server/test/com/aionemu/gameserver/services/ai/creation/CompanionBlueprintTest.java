package com.aionemu.gameserver.services.ai.creation;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import com.aionemu.gameserver.model.PlayerClass;
import com.aionemu.gameserver.model.gameobjects.player.PlayerAppearance;

class CompanionBlueprintTest {

	static CompanionBlueprint blueprint(String name) {
		PlayerAppearance appearance = new PlayerAppearance();
		appearance.setHeightScale(1.05f);
		appearance.setHair(42);
		appearance.setSkinRGB(0xffa0b0c0);
		appearance.setVoice(3);
		return new CompanionBlueprint("ELYOS", "FEMALE", "CLERIC", name, CompanionAppearanceSnapshot.encode(appearance));
	}

	@Test void roundtripRetainsAppearanceAndNeverSharesMutableSource() {
		var profile = blueprint("Vasya");
		assertEquals(profile, CompanionBlueprint.decode(profile.encode()));
		assertEquals(42, profile.appearance().getHair());
		assertEquals(0xffa0b0c0, profile.appearance().getSkinRGB());
		assertEquals(3, profile.appearance().getVoice());
		assertEquals(1.05f, profile.appearance().getHeightScale());
		var copy = profile.appearance(); copy.setHair(7);
		assertEquals(42, profile.appearance().getHair());
		assertFalse(profile.encode().contains("source"));
	}

	@Test void labelCannotBeRemovedInjectedOrMadeTooLong() {
		assertEquals("[AI:C]Vasya", blueprint("Vasya").displayName());
		assertEquals("[AI:C]Вася", blueprint("Вася").displayName());
		assertNotEquals(CompanionBlueprint.COMPANION_PREFIX, CompanionBlueprint.CITIZEN_PREFIX);
		for (String name : new String[]{"A", "Elevenchars", "[GM]Vasya", "[AI:C]Vasya", "A B", "<xml>", "А\nБ"})
			assertThrows(IllegalArgumentException.class, () -> blueprint(name));
	}

	@Test void rejectsUnknownIncompleteOrOutOfRangeAppearance() {
		var profile = blueprint("Vasya");
		assertThrows(IllegalArgumentException.class, () -> CompanionAppearanceSnapshot.decode("{}"));
		assertThrows(IllegalArgumentException.class, () -> CompanionAppearanceSnapshot.decode(profile.appearanceJson().replace("1.05", "9.0")));
		assertThrows(IllegalArgumentException.class, () -> CompanionBlueprint.decode(profile.encode().replace("\"nickname\"", "\"items\"")));
	}

	@Test void specializationIsPermanentAndMustBelongToSourceFamily() {
		assertEquals(PlayerClass.CLERIC, CompanionBlueprint.selectClass(PlayerClass.CLERIC, null));
		assertEquals(PlayerClass.CLERIC, CompanionBlueprint.selectClass(PlayerClass.PRIEST, "cleric"));
		assertThrows(IllegalArgumentException.class, () -> CompanionBlueprint.selectClass(PlayerClass.CLERIC, "gladiator"));
		assertThrows(IllegalArgumentException.class, () -> CompanionBlueprint.selectClass(PlayerClass.PRIEST, "gladiator"));
		assertThrows(IllegalArgumentException.class, () -> CompanionBlueprint.selectClass(PlayerClass.PRIEST, null));
		assertEquals(PlayerClass.PRIEST, blueprint("Vasya").classAt(9));
		assertEquals(PlayerClass.CLERIC, blueprint("Vasya").classAt(10));
	}
}
