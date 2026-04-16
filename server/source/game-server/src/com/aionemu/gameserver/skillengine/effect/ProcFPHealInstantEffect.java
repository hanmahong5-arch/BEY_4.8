package com.aionemu.gameserver.skillengine.effect;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlType;

import com.aionemu.gameserver.model.gameobjects.player.Player;
import com.aionemu.gameserver.services.LuckService;
import com.aionemu.gameserver.skillengine.model.Effect;
import com.aionemu.gameserver.skillengine.model.HealType;

/**
 * @author ATracer
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "ProcFPHealInstantEffect")
public class ProcFPHealInstantEffect extends AbstractHealEffect {

	@Override
	public void calculate(Effect effect) {
		super.calculate(effect, HealType.FP);
	}

	@Override
	public void applyEffect(Effect effect) {
		super.applyEffect(effect, HealType.FP);
		// FP potions remap to luck momentum boost in the Luck System v2
		if (effect.getEffected() instanceof Player player)
			LuckService.onFpPotionUsed(player);
	}

	@Override
	public int getCurrentStatValue(Effect effect) {
		return effect.getEffected().getLifeStats().getCurrentFp();
	}

	@Override
	public int getMaxStatValue(Effect effect) {
		return effect.getEffected().getLifeStats().getMaxFp();
	}

}
