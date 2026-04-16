package com.aionemu.gameserver.utils;

import com.aionemu.gameserver.model.ChatType;
import com.aionemu.gameserver.network.aion.serverpackets.SM_MESSAGE;
import com.aionemu.gameserver.world.World;

/**
 * Shared utilities for custom feature broadcasts: input sanitization,
 * lord rank titles, and safe world-wide messaging.
 *
 * <p><b>Lord Rank System</b>: fortress count → prestige title.
 * <pre>
 *   1 → 男爵 (Baron)
 *   2 → 子爵 (Viscount)
 *   3 → 伯爵 (Earl)
 *   4 → 侯爵 (Marquis)
 *   5+ → 公爵 (Duke)
 * </pre>
 *
 * @author BEY_4.8 7-dim evolution
 */
public final class BroadcastUtil {

	private BroadcastUtil() {}

	private static final String[] LORD_RANKS = {"", "男爵", "子爵", "伯爵", "侯爵", "公爵"};

	/** Lord rank title by fortress count. Returns empty string for 0. */
	public static String lordRank(int fortressCount) {
		if (fortressCount <= 0)
			return "";
		return LORD_RANKS[Math.min(fortressCount, LORD_RANKS.length - 1)];
	}

	/** Format "PlayerName·爵位" if player owns fortresses, plain name otherwise. */
	public static String lordTitle(String playerName, int fortressCount) {
		String rank = lordRank(fortressCount);
		String safe = sanitize(playerName);
		return rank.isEmpty() ? safe : safe + "·" + rank;
	}

	/**
	 * Strip control characters, limit length. Prevents chat injection
	 * via crafted player names or external input in broadcasts.
	 *
	 * @param input raw string (nullable)
	 * @return sanitized string, max 64 chars
	 */
	public static String sanitize(String input) {
		if (input == null || input.isEmpty())
			return "";
		// Remove non-printable except tab/newline, then truncate
		String clean = input.replaceAll("[\\p{Cc}&&[^\t]]", "");
		return clean.length() > 64 ? clean.substring(0, 64) : clean;
	}

	/**
	 * Broadcast a bright-yellow system message to all online players.
	 * Swallows exceptions — broadcast failure must never crash the caller.
	 */
	public static void broadcastYellow(String msg) {
		if (msg == null || msg.isEmpty())
			return;
		try {
			SM_MESSAGE pkt = new SM_MESSAGE(0, null, msg, ChatType.BRIGHT_YELLOW_CENTER);
			World.getInstance().forEachPlayer(p -> PacketSendUtility.sendPacket(p, pkt));
		} catch (Exception e) {
			// swallow — non-critical
		}
	}
}
