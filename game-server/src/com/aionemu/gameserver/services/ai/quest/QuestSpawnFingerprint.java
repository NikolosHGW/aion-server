package com.aionemu.gameserver.services.ai.quest;

import com.aionemu.gameserver.model.templates.spawns.SpawnTemplate;

public record QuestSpawnFingerprint(int mapId, int npcId, float x, float y, float z, byte heading) {

	private static final float COORDINATE_TOLERANCE = 0.01f;

	public QuestSpawnFingerprint {
		if (mapId <= 0 || npcId <= 0 || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
			throw new IllegalArgumentException("Invalid quest spawn fingerprint");
	}

	public boolean matches(SpawnTemplate spawn) {
		return spawn != null && !spawn.isTemporarySpawn() && !spawn.isEventSpawn() && spawn.getWalkerId() == null
			&& spawn.getRandomWalkRange() == 0 && spawn.getWorldId() == mapId && spawn.getNpcId() == npcId
			&& Math.abs(spawn.getX() - x) <= COORDINATE_TOLERANCE && Math.abs(spawn.getY() - y) <= COORDINATE_TOLERANCE
			&& Math.abs(spawn.getZ() - z) <= COORDINATE_TOLERANCE && spawn.getHeading() == heading;
	}
}
