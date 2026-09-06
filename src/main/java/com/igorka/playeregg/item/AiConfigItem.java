package com.igorka.playeregg.item;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/** Предмет-«пульт»: ПКМ открывает меню настройки ИИ (обрабатывается на клиенте). */
public class AiConfigItem extends Item {
	public AiConfigItem(Settings settings) {
		super(settings);
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		// Экран открывает клиентский обработчик (ClientAiScreenHandler), тут просто успех.
		return TypedActionResult.success(user.getStackInHand(hand), world.isClient());
	}
}
