package com.aionemu.gameserver.configs.main;

import com.aionemu.commons.configuration.Property;

/**
 * Configuration for the AI Agent integration system.
 * Controls real-time player behavior analysis and push notifications.
 */
public class AIAgentConfig {

	/**
	 * Master switch for the AI Agent integration
	 */
	@Property(key = "gameserver.aiagent.enable", defaultValue = "true")
	public static boolean ENABLE;

	/**
	 * Base URL of the AI Agent HTTP endpoint
	 */
	@Property(key = "gameserver.aiagent.api_url", defaultValue = "http://127.0.0.1:8520")
	public static String API_URL;

	/**
	 * HTTP request timeout in milliseconds
	 */
	@Property(key = "gameserver.aiagent.timeout_ms", defaultValue = "3000")
	public static int TIMEOUT_MS;

	/**
	 * Minimum interval (seconds) between AI pushes to the same player.
	 * Prevents notification spam.
	 */
	@Property(key = "gameserver.aiagent.cooldown_seconds", defaultValue = "30")
	public static int COOLDOWN_SECONDS;

	/**
	 * Enable NPC kill event notifications
	 */
	@Property(key = "gameserver.aiagent.event.npc_kill", defaultValue = "true")
	public static boolean EVENT_NPC_KILL;

	/**
	 * Enable level-up event notifications
	 */
	@Property(key = "gameserver.aiagent.event.level_up", defaultValue = "true")
	public static boolean EVENT_LEVEL_UP;

	/**
	 * Enable quest completion event notifications
	 */
	@Property(key = "gameserver.aiagent.event.quest_complete", defaultValue = "true")
	public static boolean EVENT_QUEST_COMPLETE;

	/**
	 * Enable player death event notifications
	 */
	@Property(key = "gameserver.aiagent.event.player_death", defaultValue = "true")
	public static boolean EVENT_PLAYER_DEATH;

	/**
	 * Enable zone entry event notifications
	 */
	@Property(key = "gameserver.aiagent.event.zone_enter", defaultValue = "true")
	public static boolean EVENT_ZONE_ENTER;

	/**
	 * Push mode: CHAT = chat message, HTML = Awesomium popup, BOTH = chat + HTML
	 */
	@Property(key = "gameserver.aiagent.push_mode", defaultValue = "CHAT")
	public static String PUSH_MODE;

	/**
	 * Enable dynamic title assignment based on AI analysis
	 */
	@Property(key = "gameserver.aiagent.dynamic_titles", defaultValue = "true")
	public static boolean DYNAMIC_TITLES;

	/**
	 * Starting title ID for AI dynamic titles (must match overlay pak definitions)
	 */
	@Property(key = "gameserver.aiagent.title_id_start", defaultValue = "1001")
	public static int TITLE_ID_START;

	/**
	 * Number of dynamic title slots available
	 */
	@Property(key = "gameserver.aiagent.title_id_count", defaultValue = "50")
	public static int TITLE_ID_COUNT;
}
