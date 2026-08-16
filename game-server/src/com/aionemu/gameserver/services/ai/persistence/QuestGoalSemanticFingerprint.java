package com.aionemu.gameserver.services.ai.persistence;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.aionemu.gameserver.services.ai.quest.QuestGoalPlan;

public final class QuestGoalSemanticFingerprint {

	public static final int PLAN_VERSION = 1;

	private QuestGoalSemanticFingerprint() {
	}

	public static String sha256(QuestGoalPlan plan) {
		if (plan == null)
			throw new IllegalArgumentException("Quest goal plan must not be null");
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream out = new DataOutputStream(bytes)) {
				var fingerprint = plan.semanticFingerprint();
				out.writeInt(PLAN_VERSION);
				out.writeInt(fingerprint.questId());
				out.writeInt(fingerprint.minimumLevel());
				out.writeInt(fingerprint.prerequisiteQuestIds().size());
				for (int questId : fingerprint.prerequisiteQuestIds())
					out.writeInt(questId);
				out.writeInt(fingerprint.steps().size());
				for (var step : fingerprint.steps()) {
					out.writeUTF(step.type().name());
					out.writeInt(step.requiredCount());
					out.writeInt(step.targetIds().size());
					for (int targetId : step.targetIds())
						out.writeInt(targetId);
				}
			}
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
		} catch (IOException | NoSuchAlgorithmException e) {
			throw new IllegalStateException("Unable to fingerprint quest goal plan", e);
		}
	}
}
