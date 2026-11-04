package com.aionemu.gameserver.services.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.aionemu.gameserver.configs.main.GeoDataConfig;
import com.aionemu.gameserver.services.ai.PlayerActionGateway.MovementRejectionReason;

class CompanionGeoModeTest {

	@Test
	void incompleteGeometryModesRejectMovementBeforeAccessingTheWorld() {
		GeoDataConfig.Mode previousMode = GeoDataConfig.MODE;
		boolean previousCanSee = GeoDataConfig.CANSEE_ENABLE;
		try {
			GeoDataConfig.CANSEE_ENABLE = true;
			for (GeoDataConfig.Mode mode : new GeoDataConfig.Mode[] { null, GeoDataConfig.Mode.OFF, GeoDataConfig.Mode.MATERIALS_ONLY }) {
				GeoDataConfig.MODE = mode;
				var result = new DefaultPlayerActionGateway(null, null).checkMovement(1, 2, 3);
				assertFalse(result.success());
				assertEquals(MovementRejectionReason.GEODATA_OR_LOS_DISABLED, result.reason());
			}
			GeoDataConfig.MODE = GeoDataConfig.Mode.ON;
			GeoDataConfig.CANSEE_ENABLE = false;
			assertEquals(MovementRejectionReason.GEODATA_OR_LOS_DISABLED,
				new DefaultPlayerActionGateway(null, null).checkMovement(1, 2, 3).reason());
		} finally {
			GeoDataConfig.MODE = previousMode;
			GeoDataConfig.CANSEE_ENABLE = previousCanSee;
		}
	}
}
