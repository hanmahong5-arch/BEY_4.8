package playercommands;

import java.awt.Color;

import com.aionemu.gameserver.configs.main.CustomConfig;
import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.model.gameobjects.player.PlayerLuckState;
import com.aionemu.gameserver.services.LuckService;
import com.aionemu.gameserver.utils.ChatUtil;
import com.aionemu.gameserver.utils.chathandlers.PlayerCommand;

/**
 * Player command to inspect current luck momentum state.
 *
 * Displays:
 *  - Current luck value (%)
 *  - Pity counter (consecutive failures toward guaranteed success)
 *  - Enchant/socket success bonus (%)
 *  - Passive drop rate bonus (%)
 *  - Pet status (raises luck floor)
 */
public class Luck extends PlayerCommand {

	public Luck() {
		super("luck", "Shows your current luck momentum and bonuses.");
	}

	@Override
	public void execute(Player player, String... params) {
		if (!CustomConfig.LUCK_SYSTEM_ENABLED) {
			sendInfo(player, "The luck system is not active on this server.");
			return;
		}

		PlayerLuckState state = player.getLuckState();
		boolean hasPet = player.getPet() != null;

		// Derive floor and ceiling by calling getLuckBonus (side effect: applies natural drift)
		float luckBonus = LuckService.getLuckBonus(player);
		float dropMultiplier = LuckService.getDropMultiplier(player);
		int consecutiveFails = state.getConsecutiveFailures();

		// Combat passive stats (read raw value — no drift, no pity side-effect)
		float rawLuck = state.getRawLuckValue();
		int critBonus = LuckService.getPassiveCritBonus(player);    // 0-25 on 0-1000 scale
		int evasionBonus = LuckService.getPassiveEvasionBonus(player); // 0-20 on 0-1000 scale
		float damageSkewPct = Math.max(0f, rawLuck - 0.5f) * 100f; // 0-50% skew above neutral

		// Compute approximate luck % displayed to player
		float approxLuckPct = (luckBonus / CustomConfig.LUCK_MAX_BONUS) * 100f;
		int pityMax = 5; // matches LuckService.PITY_ACCELERATION_THRESHOLD

		// Color coding: green > 70%, yellow 40-70%, red < 40%
		String luckColor;
		if (approxLuckPct >= 70f)
			luckColor = ChatUtil.color(String.format("%.0f%%", approxLuckPct), Color.GREEN);
		else if (approxLuckPct >= 40f)
			luckColor = ChatUtil.color(String.format("%.0f%%", approxLuckPct), Color.YELLOW);
		else
			luckColor = ChatUtil.color(String.format("%.0f%%", approxLuckPct), Color.RED);

		String petStatus = hasPet
			? ChatUtil.color("Active (floor raised to 35%)", Color.GREEN)
			: ChatUtil.color("Inactive (floor at 20%)", Color.GRAY);

		String dropBonus = dropMultiplier > 1.0f
			? ChatUtil.color(String.format("+%.1f%%", (dropMultiplier - 1.0f) * 100f), Color.GREEN)
			: "None";

		// Damage roll display: shows how much rolls are biased toward max damage
		String damageRoll = damageSkewPct > 0
			? ChatUtil.color(String.format("+%.0f%% hit power", damageSkewPct), Color.CYAN)
			: ChatUtil.color("Normal", Color.GRAY);

		sendInfo(player,
			"--- Luck Status ---\n"
			+ "Luck momentum: " + luckColor + "\n"
			+ "Enchant/socket bonus: " + ChatUtil.color(String.format("+%.1f%%", luckBonus), Color.CYAN) + "\n"
			+ "Drop rate bonus: " + dropBonus + "\n"
			+ "Pity counter: " + consecutiveFails + "/" + pityMax
			+ (consecutiveFails >= pityMax ? ChatUtil.color(" [GUARANTEED NEXT!]", Color.MAGENTA) : "") + "\n"
			+ "--- Combat Passives ---\n"
			+ "Damage roll bias: " + damageRoll + "\n"
			+ "Crit rate bonus: " + ChatUtil.color(String.format("+%.1f%%", critBonus / 10f), Color.CYAN) + "\n"
			+ "Evasion bonus:   " + ChatUtil.color(String.format("+%.1f%%", evasionBonus / 10f), Color.CYAN) + "\n"
			+ "Pet: " + petStatus + "\n"
			+ "Tip: Luck rises on failure and resets on success. Use FP potions for an instant boost."
		);
	}
}
