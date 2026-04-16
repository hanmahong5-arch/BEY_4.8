package com.aionemu.gameserver.configs.main;

import java.util.Set;

import org.quartz.CronExpression;

import com.aionemu.commons.configuration.Property;

public class CustomConfig {

	/**
	 * Enables challenge tasks
	 */
	@Property(key = "gameserver.challenge.tasks.enabled", defaultValue = "false")
	public static boolean CHALLENGE_TASKS_ENABLED;

	/**
	 * Announce when a player successfully enchants an item to +15 or +20
	 */
	@Property(key = "gameserver.enchant.announce.enable", defaultValue = "true")
	public static boolean ENABLE_ENCHANT_ANNOUNCE;

	/**
	 * Enable speaking between factions
	 */
	@Property(key = "gameserver.chat.factions.enable", defaultValue = "false")
	public static boolean SPEAKING_BETWEEN_FACTIONS;

	/**
	 * Minimum level to use whisper
	 */
	@Property(key = "gameserver.chat.whisper.level", defaultValue = "10")
	public static int LEVEL_TO_WHISPER;

	/**
	 * Time in days after which an item in broker will be unregistered (client cannot display more than 255 days)
	 */
	@Property(key = "gameserver.broker.registration_expiration_days", defaultValue = "8")
	public static int BROKER_REGISTRATION_EXPIRATION_DAYS;

	/**
	 * Factions search mode
	 */
	@Property(key = "gameserver.search.factions.mode", defaultValue = "false")
	public static boolean FACTIONS_SEARCH_MODE;

	/**
	 * list gm when search players
	 */
	@Property(key = "gameserver.search.gm.list", defaultValue = "false")
	public static boolean SEARCH_GM_LIST;

	/**
	 * Minimum level to use search
	 */
	@Property(key = "gameserver.search.player.level", defaultValue = "10")
	public static int LEVEL_TO_SEARCH;

	/**
	 * Allow opposite factions to bind in enemy territories
	 */
	@Property(key = "gameserver.cross.faction.binding", defaultValue = "false")
	public static boolean ENABLE_CROSS_FACTION_BINDING;

	/**
	 * Enable second class change without quest
	 */
	@Property(key = "gameserver.simple.secondclass.enable", defaultValue = "false")
	public static boolean ENABLE_SIMPLE_2NDCLASS;

	/**
	 * Disable chain trigger rate (chain skill with 100% success)
	 */
	@Property(key = "gameserver.skill.chain.disable_triggerrate", defaultValue = "false")
	public static boolean SKILL_CHAIN_DISABLE_TRIGGERRATE;

	/**
	 * Base Fly Time
	 */
	@Property(key = "gameserver.base.flytime", defaultValue = "60")
	public static int BASE_FLYTIME;

	@Property(key = "gameserver.friendlist.gm_restrict", defaultValue = "false")
	public static boolean FRIENDLIST_GM_RESTRICT;

	/**
	 * Friendlist size
	 */
	@Property(key = "gameserver.friendlist.size", defaultValue = "90")
	public static int FRIENDLIST_SIZE;

	/**
	 * Basic Quest limit size
	 */
	@Property(key = "gameserver.basic.questsize.limit", defaultValue = "40")
	public static int BASIC_QUEST_SIZE_LIMIT;

	/**
	 * Total number of allowed cube expansions
	 */
	@Property(key = "gameserver.cube.expansion_limit", defaultValue = "11")
	public static int CUBE_EXPANSION_LIMIT;

	/**
	 * Npc Cube Expands limit size
	 */
	@Property(key = "gameserver.npcexpands.limit", defaultValue = "5")
	public static int NPC_CUBE_EXPANDS_SIZE_LIMIT;

	/**
	 * Enable Kinah cap
	 */
	@Property(key = "gameserver.enable.kinah.cap", defaultValue = "false")
	public static boolean ENABLE_KINAH_CAP;

	/**
	 * Kinah cap value
	 */
	@Property(key = "gameserver.kinah.cap.value", defaultValue = "999999999")
	public static long KINAH_CAP_VALUE;

	/**
	 * Enable AP cap
	 */
	@Property(key = "gameserver.enable.ap.cap", defaultValue = "false")
	public static boolean ENABLE_AP_CAP;

	/**
	 * AP cap value
	 */
	@Property(key = "gameserver.ap.cap.value", defaultValue = "1000000")
	public static long AP_CAP_VALUE;

	/**
	 * Enable no AP in mentored group.
	 */
	@Property(key = "gameserver.noap.mentor.group", defaultValue = "false")
	public static boolean MENTOR_GROUP_AP;

	/**
	 * .faction cfg
	 */
	@Property(key = "gameserver.faction.price", defaultValue = "10000")
	public static int FACTION_USE_PRICE;

	@Property(key = "gameserver.faction.cmdchannel", defaultValue = "true")
	public static boolean FACTION_CMD_CHANNEL;

	@Property(key = "gameserver.faction.chatchannels", defaultValue = "false")
	public static boolean FACTION_CHAT_CHANNEL;

	/**
	 * Time in milliseconds in which players are limited for killing one player
	 */
	@Property(key = "gameserver.pvp.dayduration", defaultValue = "86400000")
	public static long PVP_DAY_DURATION;

	/**
	 * Allowed Kills in configuered time for full AP. Move to separate config when more pvp options.
	 */
	@Property(key = "gameserver.pvp.maxkills", defaultValue = "5")
	public static int MAX_DAILY_PVP_KILLS;

	/**
	 * Add a reward to player for pvp kills
	 */
	@Property(key = "gameserver.kill.reward.enable", defaultValue = "false")
	public static boolean ENABLE_KILL_REWARD;

	/**
	 * Keep buffs when getting killed in Sanctum's Coliseum or Pandaemonium's Triniel Coliseum
	 */
	@Property(key = "gameserver.coliseum.keep_buffs", defaultValue = "false")
	public static boolean KEEP_BUFFS_IN_COLISEUM;

	/**
	 * Enable one kisk restriction
	 */
	@Property(key = "gameserver.kisk.restriction.enable", defaultValue = "true")
	public static boolean ENABLE_KISK_RESTRICTION;

	@Property(key = "gameserver.rift.enable", defaultValue = "true")
	public static boolean RIFT_ENABLED;
	@Property(key = "gameserver.rift.duration", defaultValue = "1")
	public static int RIFT_DURATION;

	@Property(key = "gameserver.vortex.enable", defaultValue = "true")
	public static boolean VORTEX_ENABLED;
	@Property(key = "gameserver.vortex.brusthonin.schedule", defaultValue = "0 0 16 ? * SAT")
	public static CronExpression VORTEX_BRUSTHONIN_SCHEDULE;
	@Property(key = "gameserver.vortex.theobomos.schedule", defaultValue = "0 0 16 ? * SUN")
	public static CronExpression VORTEX_THEOBOMOS_SCHEDULE;
	@Property(key = "gameserver.vortex.duration", defaultValue = "1")
	public static int VORTEX_DURATION;

	@Property(key = "gameserver.cp.enable", defaultValue = "true")
	public static boolean CONQUEROR_AND_PROTECTOR_SYSTEM_ENABLED;
	@Property(key = "gameserver.cp.worlds", defaultValue = "210020000,210040000,210050000,210070000,220020000,220040000,220070000,220080000")
	public static Set<Integer> CONQUEROR_AND_PROTECTOR_WORLDS;
	@Property(key = "gameserver.cp.level.diff", defaultValue = "5")
	public static int CONQUEROR_AND_PROTECTOR_LEVEL_DIFF;
	@Property(key = "gameserver.cp.kills.decrease_interval_minutes", defaultValue = "10")
	public static int CONQUEROR_AND_PROTECTOR_KILLS_DECREASE_INTERVAL;
	@Property(key = "gameserver.cp.kills.decrease_count", defaultValue = "1")
	public static int CONQUEROR_AND_PROTECTOR_KILLS_DECREASE_COUNT;
	@Property(key = "gameserver.cp.kills.rank1", defaultValue = "1")
	public static int CONQUEROR_AND_PROTECTOR_KILLS_RANK1;
	@Property(key = "gameserver.cp.kills.rank2", defaultValue = "10")
	public static int CONQUEROR_AND_PROTECTOR_KILLS_RANK2;
	@Property(key = "gameserver.cp.kills.rank3", defaultValue = "20")
	public static int CONQUEROR_AND_PROTECTOR_KILLS_RANK3;

	/**
	 * Limits Config
	 */
	@Property(key = "gameserver.limits.enable", defaultValue = "true")
	public static boolean LIMITS_ENABLED;

	@Property(key = "gameserver.limits.enable_dynamic_cap", defaultValue = "false")
	public static boolean LIMITS_ENABLE_DYNAMIC_CAP;

	@Property(key = "gameserver.limits.update", defaultValue = "0 0 0 ? * *")
	public static CronExpression LIMITS_UPDATE;

	@Property(key = "gameserver.abyssxform.afterlogout", defaultValue = "false")
	public static boolean ABYSSXFORM_LOGOUT;

	@Property(key = "gameserver.ride.restriction.enable", defaultValue = "true")
	public static boolean ENABLE_RIDE_RESTRICTION;

	/**
	 * Enables sell apitems
	 */
	@Property(key = "gameserver.selling.apitems.enabled", defaultValue = "true")
	public static boolean SELLING_APITEMS_ENABLED;

	@Property(key = "character.deletion.time.minutes", defaultValue = "5")
	public static int CHARACTER_DELETION_TIME_MINUTES;

	/**
	 * Custom Reward Packages
	 */
	@Property(key = "gameserver.custom.starter_kit.enable", defaultValue = "false")
	public static boolean ENABLE_STARTER_KIT;

	@Property(key = "gameserver.pvpmap.enable", defaultValue = "false")
	public static boolean PVP_MAP_ENABLED;

	@Property(key = "gameserver.pvpmap.apmultiplier", defaultValue = "2")
	public static float PVP_MAP_AP_MULTIPLIER;

	@Property(key = "gameserver.pvpmap.pve.apmultiplier", defaultValue = "1")
	public static float PVP_MAP_PVE_AP_MULTIPLIER;

	@Property(key = "gameserver.pvpmap.random_boss.rate", defaultValue = "40")
	public static int PVP_MAP_RANDOM_BOSS_BASE_RATE;

	@Property(key = "gameserver.pvpmap.random_boss.time", defaultValue = "0 30 14,18,21 ? * *")
	public static CronExpression PVP_MAP_RANDOM_BOSS_SCHEDULE;

	@Property(key = "gameserver.rates.godstone.activation.rate", defaultValue = "1.0")
	public static float GODSTONE_ACTIVATION_RATE;

	@Property(key = "gameserver.rates.godstone.evaluation.cooldown_millis", defaultValue = "750")
	public static int GODSTONE_EVALUATION_COOLDOWN_MILLIS;

	/**
	 * Free trade: all items become tradeable (player trade, broker, mail, private store).
	 * Only quest items remain non-tradeable. Soul-bind checks are also bypassed.
	 * Enables a player-driven economy where items have real circulation value.
	 */
	@Property(key = "gameserver.custom.free_trade", defaultValue = "true")
	public static boolean FREE_TRADE;

	/**
	 * NPC displaced linger time (ms) — how long an NPC stays at its chase
	 * endpoint after losing aggro, before returning to spawn. Creates a
	 * "living world" feel where NPC displacement is visible to other players.
	 * Set to 0 to disable (instant return).
	 */
	@Property(key = "gameserver.custom.npc_displaced_linger_ms", defaultValue = "120000")
	public static long NPC_DISPLACED_LINGER_MS;

	/**
	 * NPC alert duration (ms) after returning from combat.
	 * During alert: aggro range x1.5, faster reaction.
	 */
	@Property(key = "gameserver.custom.npc_alert_duration_ms", defaultValue = "180000")
	public static long NPC_ALERT_DURATION_MS;

	/**
	 * Free flight: remove flight time limits for all players.
	 * Flight is the most distinctive mechanic in Aion — limiting it
	 * is anti-fun on a private server with no monetization pressure.
	 */
	@Property(key = "gameserver.custom.free_flight", defaultValue = "true")
	public static boolean FREE_FLIGHT;

	/**
	 * Disable soul-binding on equip. Combined with FREE_TRADE,
	 * this means any item can be traded even after being worn.
	 * Creates a real secondary market for used gear.
	 */
	@Property(key = "gameserver.custom.disable_soul_bind", defaultValue = "true")
	public static boolean DISABLE_SOUL_BIND;

	/**
	 * Skip item identification: items drop pre-identified with stats already rolled.
	 * Removes the identification scroll busywork + RNG layer that adds no strategic depth.
	 */
	@Property(key = "gameserver.custom.skip_identification", defaultValue = "true")
	public static boolean SKIP_IDENTIFICATION;

	/**
	 * Tempering always succeeds: removes the reset-to-0 failure and PLUME destruction.
	 * Keeps the material cost as the progression gate instead of RNG punishment.
	 */
	@Property(key = "gameserver.custom.tempering_always_success", defaultValue = "true")
	public static boolean TEMPERING_ALWAYS_SUCCESS;

	/**
	 * Stigma enchant failure no longer destroys the stigma stone.
	 * Instead, enchant level decreases by 1 (min 0). Backport from 5.1 design.
	 * Keeps the risk/reward loop without the devastating total-loss outcome.
	 */
	@Property(key = "gameserver.custom.stigma_no_destroy", defaultValue = "true")
	public static boolean STIGMA_NO_DESTROY;

	/**
	 * Remove arena entry ticket requirements.
	 * Players can enter PvP arenas without collecting tickets first.
	 * Removes a pointless gate between players and PvP content.
	 */
	@Property(key = "gameserver.custom.arena_no_tickets", defaultValue = "true")
	public static boolean ARENA_NO_TICKETS;

	/**
	 * Idian stones never lose charges — buff is permanent once socketed.
	 * Removes the maintenance tax of re-farming consumable buff items.
	 */
	@Property(key = "gameserver.custom.idian_permanent", defaultValue = "true")
	public static boolean IDIAN_PERMANENT;

	/**
	 * Remove abyss rank restriction on gear.
	 * Any player can equip rank-gated abyss gear regardless of PvP rank.
	 * Solves the chicken-and-egg problem: need gear to rank up, need rank to get gear.
	 */
	@Property(key = "gameserver.custom.no_rank_restriction", defaultValue = "true")
	public static boolean NO_RANK_RESTRICTION;

	/**
	 * One-click gathering: node completes in a single attempt instead of 3-5.
	 * Total yield is multiplied by the original harvest count so no items are lost.
	 * Backport from 5.x official QoL change.
	 */
	@Property(key = "gameserver.custom.gathering_one_click", defaultValue = "true")
	public static boolean GATHERING_ONE_CLICK;

	/**
	 * Equipment charge points never decay from combat.
	 * Removes the "conditioning" maintenance tax — once charged, always charged.
	 */
	@Property(key = "gameserver.custom.charge_no_decay", defaultValue = "true")
	public static boolean CHARGE_NO_DECAY;

	// ============================
	// Luck System v2 — 心流幸运 (Flow Luck)
	// FP体系重生为幸运值：不消耗，基于动量(失败↑成功↓)
	// ============================

	/**
	 * Enable the Luck System v2: momentum-based luck with pity protection.
	 * FP gear = luck ceiling, FP potions = luck boost, pets = luck floor raise.
	 * No resource consumption — luck flows naturally.
	 */
	@Property(key = "gameserver.custom.luck_system_enabled", defaultValue = "true")
	public static boolean LUCK_SYSTEM_ENABLED;

	/**
	 * Maximum luck bonus percentage when luck value is at 100%.
	 * Applied additively: base 70% + luck 12% = 82% success.
	 */
	@Property(key = "gameserver.custom.luck_max_bonus", defaultValue = "15")
	public static float LUCK_MAX_BONUS;

	/**
	 * Maximum passive drop rate bonus from luck (percentage).
	 * Based on maxFP from gear. Every 100 FP above base = +1%.
	 */
	@Property(key = "gameserver.custom.luck_max_drop_bonus", defaultValue = "10")
	public static float LUCK_MAX_DROP_BONUS;

	// ============================
	// Smart Cast — 智能瞄准 (Directional Auto-Targeting)
	// 无目标时自动锁定面前最近敌人，消除强制点选目标操作
	// ============================

	/**
	 * Enable smart-cast auto-targeting for all offensive single-target skills.
	 * When a player activates a TARGET-type skill with no target selected, the server
	 * automatically locks onto the nearest enemy within the forward-facing cone.
	 * Cone direction: toward current target if alive, otherwise player's heading.
	 */
	@Property(key = "gameserver.custom.smart_cast_enabled", defaultValue = "true")
	public static boolean SMART_CAST_ENABLED;

	/**
	 * Full cone angle (degrees) used for smart-cast enemy search.
	 * 120 = ±60° from the facing axis — wide enough to feel responsive, narrow
	 * enough to preserve directional intent (enemies directly behind never grabbed).
	 * Range: 20 (sniper-precise) to 180 (hemisphere).
	 */
	@Property(key = "gameserver.custom.smart_cast_cone_angle", defaultValue = "120")
	public static float SMART_CAST_CONE_ANGLE;

	/**
	 * 360° fallback for smart-cast: after the forward cone search finds nothing,
	 * a second pass finds the nearest living enemy anywhere within the skill's range,
	 * regardless of direction.  This virtually eliminates "cannot find target" errors
	 * when enemies are nearby but outside the facing cone — making combat feel fluid
	 * and free without requiring precise mouse targeting.
	 */
	@Property(key = "gameserver.custom.smart_cast_fallback_360", defaultValue = "true")
	public static boolean SMART_CAST_FALLBACK_360;

	// ============================
	// Swarm Intelligence — 群体智慧 (Ant Colony + Multi-Head Attention)
	// NPC通过信息素场（stigmergy）共享威胁信息，形成蜂群式协调战斗行为。
	// ============================

	/**
	 * Enable the Swarm Intelligence system.
	 * NPCs deposit pheromone on combat entry and death; nearby same-tribe
	 * allies pick up the signal, refuse to disengage, and broadcast hate.
	 */
	@Property(key = "gameserver.custom.swarm_enabled", defaultValue = "true")
	public static boolean SWARM_ENABLED;

	/**
	 * Enable swarm debug instrumentation.
	 * When false (default), all telemetry record-sites are no-ops: one volatile
	 * read + branch and nothing else. When true, SwarmTelemetry collects counters,
	 * timing reservoirs, and decision ring-buffers readable via //swarm admin
	 * commands, and each target-switch decision is written to swarm_decisions.log.
	 * Dev-only; should be OFF in prod.
	 */
	@Property(key = "gameserver.custom.swarm_debug_enabled", defaultValue = "false")
	public static boolean SWARM_DEBUG_ENABLED;

	/**
	 * Enable idle NPCs following pheromone gradients toward nearby combat.
	 * This is the feature that caused "NPC moving back and forth" oscillation
	 * when NPCs followed their own residual pheromone trails. Keep default OFF
	 * until the tail-chase prevention is validated in-game.
	 */
	@Property(key = "gameserver.custom.swarm_investigate_enabled", defaultValue = "false")
	public static boolean SWARM_INVESTIGATE_ENABLED;

	// ============================
	// Utility AI — opt-in long-term-goal layer for marked NPCs
	// ============================

	/**
	 * Enable the utility AI subsystem. When false, all NPCs use only the
	 * existing AI templates — no per-NPC overhead anywhere.
	 */
	@Property(key = "gameserver.custom.utility_ai_enabled", defaultValue = "false")
	public static boolean UTILITY_AI_ENABLED;

	/**
	 * Comma-separated list of NPC template IDs to opt in to utility AI goals.
	 * Only these NPCs will have their idle behaviour replaced by the
	 * Patrol/Defend/Rest goal selector. Empty = no NPCs.
	 */
	@Property(key = "gameserver.custom.utility_ai_npc_ids", defaultValue = "")
	public static String UTILITY_AI_NPC_IDS;

	// ============================
	// FFA Mode — 传奇全体攻击模式 (Free-For-All PK Mode)
	// Player casts Bandage Heal (skill 245) → 4s channel → toggles FFA.
	// In FFA: all races/NPCs become hostile; death drops equipped items
	// into a public loot chest spawned at death location.
	// ============================

	/** Master kill-switch for the whole FFA mode subsystem. */
	@Property(key = "gameserver.custom.ffa_mode_enabled", defaultValue = "true")
	public static boolean FFA_MODE_ENABLED;

	/**
	 * Skill ID whose successful cast toggles FFA mode instead of its normal effect.
	 * Default 245 = "Bandage Heal" (4s cast, universal, natural visual feedback).
	 * Set to 0 to disable the skill hijack without killing the rest of FFA.
	 */
	@Property(key = "gameserver.custom.ffa_trigger_skill_id", defaultValue = "245")
	public static int FFA_TRIGGER_SKILL_ID;

	/** Cooldown between consecutive FFA toggles (ms). Prevents mode flapping. */
	@Property(key = "gameserver.custom.ffa_toggle_cooldown_ms", defaultValue = "30000")
	public static long FFA_TOGGLE_COOLDOWN_MS;

	/**
	 * NPC template ID of the loot chest spawned at a PK death location.
	 * 230103 = "entrance treasure chest (temp)" — neutral, noaction AI, interactable.
	 */
	@Property(key = "gameserver.custom.ffa_loot_chest_npc_id", defaultValue = "230103")
	public static int FFA_LOOT_CHEST_NPC_ID;

	/** How long the loot chest persists before despawn (ms). */
	@Property(key = "gameserver.custom.ffa_loot_chest_lifetime_ms", defaultValue = "180000")
	public static long FFA_LOOT_CHEST_LIFETIME_MS;

	/** Number of random equipped items to drop on FFA death. */
	@Property(key = "gameserver.custom.ffa_drop_item_count", defaultValue = "1")
	public static int FFA_DROP_ITEM_COUNT;

	/**
	 * Maximum FFA toggles allowed per hour per player. Anti-macro hard cap
	 * on top of the per-toggle cooldown. 0 disables the limit.
	 */
	@Property(key = "gameserver.custom.ffa_max_toggles_per_hour", defaultValue = "20")
	public static int FFA_MAX_TOGGLES_PER_HOUR;

	/**
	 * Every N consecutive FFA kills trigger a server-wide broadcast.
	 * E.g. 3 → announce at 3, 6, 9... kills. 0 disables streaks.
	 */
	@Property(key = "gameserver.custom.ffa_kill_streak_broadcast_threshold", defaultValue = "3")
	public static int FFA_KILL_STREAK_BROADCAST_THRESHOLD;

	// ============================
	// NPC Hardcore Tier — stat multipliers + loot expansion
	// 硬核档位：NPC 更强，但会按概率掉自己穿的装备和卖的商品。
	// ============================

	/** HP multiplier applied to every spawned NPC. Hardcore tier: 1.5x. */
	@Property(key = "gameserver.custom.npc_hp_multiplier", defaultValue = "1.5")
	public static double NPC_HP_MULTIPLIER;

	/** Physical + magical attack multiplier for NPCs. Hardcore tier: 1.35x. */
	@Property(key = "gameserver.custom.npc_atk_multiplier", defaultValue = "1.35")
	public static double NPC_ATK_MULTIPLIER;

	/** Movement speed multiplier for NPCs. Hardcore tier: 1.1x. */
	@Property(key = "gameserver.custom.npc_speed_multiplier", defaultValue = "1.1")
	public static double NPC_SPEED_MULTIPLIER;

	/**
	 * Per-slot drop chance for NPC visible equipment.
	 * 0.02 = 2% per equipped slot. A 6-slot NPC has ~11% chance to drop at least one.
	 */
	@Property(key = "gameserver.custom.npc_equip_drop_chance", defaultValue = "0.02")
	public static double NPC_EQUIP_DROP_CHANCE;

	/**
	 * Per-item drop chance for NPC merchant sell list items.
	 * 0.005 = 0.5% per sell-list entry. Applies only to NPCs that are merchants.
	 */
	@Property(key = "gameserver.custom.npc_sell_drop_chance", defaultValue = "0.005")
	public static double NPC_SELL_DROP_CHANCE;

	/** Multiplier applied to equip + sell drop chances when the NPC is a boss. */
	@Property(key = "gameserver.custom.npc_boss_drop_mult", defaultValue = "3.0")
	public static double NPC_BOSS_DROP_MULT;

	// ============================
	// Solo Fortress Ownership — 单人要塞
	// Allows the top-damaging individual player to become sole lord of a
	// fortress (bypassing the legion-only path), with supporting prestige
	// mechanics: broadcast, lord buff, hourly tax, inactivity decay.
	// ============================

	/** Master kill-switch for single-player fortress ownership. */
	@Property(key = "gameserver.custom.solo_fortress_enabled", defaultValue = "true")
	public static boolean SOLO_FORTRESS_ENABLED;

	/**
	 * When true, solo ownership takes priority even if the top damager is in a legion.
	 * When false, solo ownership only activates if the top damager has no legion.
	 */
	@Property(key = "gameserver.custom.solo_fortress_prefer_solo", defaultValue = "true")
	public static boolean SOLO_FORTRESS_PREFER_SOLO;

	/**
	 * Skill ID applied to the lord while inside their owned fortress zone.
	 * 0 = disabled (no buff). Any vanilla passive/stat skill id works here.
	 */
	@Property(key = "gameserver.custom.solo_fortress_lord_buff_skill_id", defaultValue = "0")
	public static int SOLO_FORTRESS_LORD_BUFF_SKILL_ID;

	/** Hourly tax kinah mailed to each fortress lord. Scales per tier via multiplier below. */
	@Property(key = "gameserver.custom.solo_fortress_hourly_tax_kinah", defaultValue = "500000")
	public static long SOLO_FORTRESS_HOURLY_TAX_KINAH;

	/** Extra multiplier applied per occupy_count tier (prestige stacks → more gold). */
	@Property(key = "gameserver.custom.solo_fortress_tax_tier_mult", defaultValue = "0.25")
	public static double SOLO_FORTRESS_TAX_TIER_MULT;

	/**
	 * Days of lord inactivity before the fortress automatically resets to Balaur.
	 * Prevents squatters from hoarding a fortress they never defend.
	 */
	@Property(key = "gameserver.custom.solo_fortress_decay_days", defaultValue = "7")
	public static int SOLO_FORTRESS_DECAY_DAYS;

	/** Interval (ms) for the background tax + decay sweep. Default: 1 hour. */
	@Property(key = "gameserver.custom.solo_fortress_sweep_interval_ms", defaultValue = "3600000")
	public static long SOLO_FORTRESS_SWEEP_INTERVAL_MS;

	/**
	 * Base AP bounty awarded to a player who slays a fortress lord.
	 * Total bounty = BASE × (number of fortresses the victim owns).
	 */
	@Property(key = "gameserver.custom.solo_fortress_bounty_ap", defaultValue = "10000")
	public static int SOLO_FORTRESS_BOUNTY_AP;
}
