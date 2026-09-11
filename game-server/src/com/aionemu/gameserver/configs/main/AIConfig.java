package com.aionemu.gameserver.configs.main;

import java.io.File;

import com.aionemu.commons.configuration.Property;

/**
 * @author ATracer
 */
public class AIConfig {

	@Property(key = "ai.enabled", defaultValue = "false")
	public static boolean ENABLED;

	@Property(key = "ai.synthetic_players.enabled", defaultValue = "false")
	public static boolean SYNTHETIC_PLAYERS_ENABLED;

	@Property(key = "ai.companions.enabled", defaultValue = "false")
	public static boolean COMPANIONS_ENABLED;

	@Property(key = "ai.companions.quest_goals.enabled", defaultValue = "false")
	public static boolean COMPANION_QUEST_GOALS_ENABLED;

	@Property(key = "ai.companions.quest_goals.allowed_quest_ids", defaultValue = "")
	public static String COMPANION_QUEST_GOAL_ALLOWED_IDS;

	@Property(key = "ai.companions.goal_persistence.enabled", defaultValue = "false")
	public static boolean COMPANION_GOAL_PERSISTENCE_ENABLED;

	@Property(key = "ai.companions.creation.enabled", defaultValue = "false")
	public static boolean COMPANION_CREATION_ENABLED;

	@Property(key = "ai.companions.creation.host_account_id", defaultValue = "0")
	public static int COMPANION_CREATION_HOST_ACCOUNT_ID;

	@Property(key = "ai.companions.creation.host_account_name", defaultValue = "")
	public static String COMPANION_CREATION_HOST_ACCOUNT_NAME;

	@Property(key = "ai.companions.quest_execution.enabled", defaultValue = "false")
	public static boolean COMPANION_QUEST_EXECUTION_ENABLED;

	@Property(key = "ai.companions.quest_execution.combat_enabled", defaultValue = "false")
	public static boolean COMPANION_QUEST_EXECUTION_COMBAT_ENABLED;

	@Property(key = "ai.companions.quest_execution.allowed_quest_ids", defaultValue = "")
	public static String COMPANION_QUEST_EXECUTION_ALLOWED_IDS;

	@Property(key = "ai.companions.quest_execution.poll_interval_ms", defaultValue = "1000")
	public static long COMPANION_QUEST_EXECUTION_POLL_INTERVAL_MS;

	@Property(key = "ai.companions.combat.enabled", defaultValue = "false")
	public static boolean COMPANION_COMBAT_ENABLED;

	@Property(key = "ai.companions.combat.basic_attack.enabled", defaultValue = "false")
	public static boolean COMPANION_BASIC_ATTACK_ENABLED;

	@Property(key = "ai.companions.combat.owner_attribution.enabled", defaultValue = "false")
	public static boolean COMPANION_OWNER_ATTRIBUTION_ENABLED;

	@Property(key = "ai.companions.combat.allowed_npc_ids", defaultValue = "")
	public static String COMPANION_COMBAT_ALLOWED_NPC_IDS;

	@Property(key = "ai.companions.combat.owner_signal_ttl_ms", defaultValue = "3000")
	public static long COMPANION_COMBAT_OWNER_SIGNAL_TTL_MS;

	@Property(key = "ai.companions.template_account_id", defaultValue = "0")
	public static int COMPANION_TEMPLATE_ACCOUNT_ID;

	@Property(key = "ai.companions.template_character_id", defaultValue = "0")
	public static int COMPANION_TEMPLATE_CHARACTER_ID;

	@Property(key = "ai.companions.follow_start_distance", defaultValue = "6")
	public static float COMPANION_FOLLOW_START_DISTANCE;

	@Property(key = "ai.companions.follow_stop_distance", defaultValue = "3")
	public static float COMPANION_FOLLOW_STOP_DISTANCE;

	@Property(key = "ai.companions.follow_offset_distance", defaultValue = "2")
	public static float COMPANION_FOLLOW_OFFSET_DISTANCE;

	@Property(key = "ai.companions.spawn_offset_distance", defaultValue = "2")
	public static float COMPANION_SPAWN_OFFSET_DISTANCE;

	@Property(key = "ai.companions.collision_tolerance", defaultValue = "0.75")
	public static float COMPANION_COLLISION_TOLERANCE;

	@Property(key = "ai.companions.destination_update_interval_ms", defaultValue = "400")
	public static long COMPANION_DESTINATION_UPDATE_INTERVAL_MS;

	@Property(key = "ai.companions.blocked_retry_interval_ms", defaultValue = "1000")
	public static long COMPANION_BLOCKED_RETRY_INTERVAL_MS;

	@Property(key = "ai.citizens.enabled", defaultValue = "false")
	public static boolean CITIZENS_ENABLED;

	@Property(key = "ai.economy.enabled", defaultValue = "false")
	public static boolean ECONOMY_ENABLED;

	@Property(key = "ai.synthetic_players.template_account_id", defaultValue = "0")
	public static int SYNTHETIC_TEMPLATE_ACCOUNT_ID;

	@Property(key = "ai.synthetic_players.template_character_id", defaultValue = "0")
	public static int SYNTHETIC_TEMPLATE_CHARACTER_ID;

	@Property(key = "ai.synthetic_players.runtime_name_prefix", defaultValue = "[AI]")
	public static String SYNTHETIC_RUNTIME_NAME_PREFIX;

	@Property(key = "ai.synthetic_players.runtime_name_max_length", defaultValue = "16")
	public static int SYNTHETIC_RUNTIME_NAME_MAX_LENGTH;

	@Property(key = "ai.synthetic_players.route_offsets", defaultValue = "2,0;10,0;10,8;2,8")
	public static String SYNTHETIC_ROUTE_OFFSETS;

	@Property(key = "ai.synthetic_players.route_max_segment_length", defaultValue = "20")
	public static float SYNTHETIC_ROUTE_MAX_SEGMENT_LENGTH;

	@Property(key = "ai.synthetic_players.route_arrival_tolerance", defaultValue = "0.35")
	public static float SYNTHETIC_ROUTE_ARRIVAL_TOLERANCE;

	@Property(key = "ai.synthetic_players.route_collision_tolerance", defaultValue = "0.75")
	public static float SYNTHETIC_ROUTE_COLLISION_TOLERANCE;

	@Property(key = "ai.synthetic_players.scheduler_period_ms", defaultValue = "200")
	public static int SYNTHETIC_SCHEDULER_PERIOD_MS;

	@Property(key = "ai.synthetic_players.scheduler_max_actions", defaultValue = "1")
	public static int SYNTHETIC_SCHEDULER_MAX_ACTIONS;

	@Property(key = "ai.synthetic_players.scheduler_budget_ms", defaultValue = "2")
	public static long SYNTHETIC_SCHEDULER_BUDGET_MS;

	/**
	 * Debug (for developers)
	 */
	@Property(key = "gameserver.ai.move.debug", defaultValue = "true")
	public static boolean MOVE_DEBUG;

	@Property(key = "gameserver.ai.event.debug", defaultValue = "false")
	public static boolean EVENT_DEBUG;

	@Property(key = "gameserver.ai.oncreate.debug", defaultValue = "false")
	public static boolean ONCREATE_DEBUG;

	/**
	 * Enable NPC movement
	 */
	@Property(key = "gameserver.npcmovement.enable", defaultValue = "true")
	public static boolean ACTIVE_NPC_MOVEMENT;

	/**
	 * Minimum movement delay
	 */
	@Property(key = "gameserver.npcmovement.delay.minimum", defaultValue = "3")
	public static int MINIMIMUM_DELAY;

	/**
	 * Maximum movement delay
	 */
	@Property(key = "gameserver.npcmovement.delay.maximum", defaultValue = "15")
	public static int MAXIMUM_DELAY;

	/**
	 * Npc Shouts activator
	 */
	@Property(key = "gameserver.npcshouts.enable", defaultValue = "false")
	public static boolean SHOUTS_ENABLE;

	/**
	 * Location of AI *.java handlers
	 */
	@Property(key = "gameserver.ai.handler_directory", defaultValue = "./data/handlers/ai")
	public static File HANDLER_DIRECTORY;
}
