package com.aionemu.gameserver.services.ai.memory;

import java.sql.Timestamp;
import java.util.UUID;

/** Immutable observation, not a confirmed event. Never retains a Player or QuestState. */
public record QuestCompletionEvidence(String realm, int ownerId, int companionId, UUID claimToken,
	int questId, int completeCount, long completedSecond, long observedMillis, long sessionGeneration,
	int mapId, int instanceId, double distance) {
	public static final int QUEST = 1102;
	public static final int MAP = 210010000;
	public static final int NPC = 203057;
	public static final double MAX_DISTANCE = 10;

	public QuestCompletionEvidence {
		if (!validRealm(realm) || ownerId <= 0 || companionId <= 0 || ownerId == companionId || claimToken == null
			|| questId != QUEST || completeCount != 1 || completedSecond <= 0 || observedMillis <= 0 || sessionGeneration <= 0
			|| mapId != MAP || instanceId <= 0 || !Double.isFinite(distance) || distance < 0 || distance > MAX_DISTANCE)
			throw new IllegalArgumentException("Invalid W0 witness evidence");
	}

	public static boolean validRealm(String realm) { return realm != null && realm.matches("[a-zA-Z0-9_-]{1,48}"); }
	public static long second(Timestamp value) { return value == null ? 0 : Math.floorDiv(value.getTime(), 1000); }

	/** Re-observing the same completion may have a different queue time/lease/distance. */
	public boolean sameOccurrence(QuestCompletionEvidence other) {
		return realm.equals(other.realm) && ownerId == other.ownerId && companionId == other.companionId
			&& claimToken.equals(other.claimToken) && questId == other.questId && completeCount == other.completeCount
			&& completedSecond == other.completedSecond && mapId == other.mapId && instanceId == other.instanceId;
	}
}
