package com.igorka.playeregg;

import com.igorka.playeregg.ai.ServerAiSettings;
import com.igorka.playeregg.entity.ClonePlayerEntity;
import com.igorka.playeregg.item.AiConfigItem;
import com.igorka.playeregg.net.AiNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlayerEggMod implements ModInitializer {
	public static final String MOD_ID = "playeregg";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** NPC-игрок под управлением ИИ. */
	public static final EntityType<ClonePlayerEntity> CLONE_PLAYER = Registry.register(
			Registries.ENTITY_TYPE,
			new Identifier(MOD_ID, "clone_player"),
			FabricEntityTypeBuilder.create(SpawnGroup.MISC, ClonePlayerEntity::new)
					.dimensions(EntityDimensions.changing(0.6F, 1.8F))
					.trackRangeBlocks(64)
					.build()
	);

	/** Яйцо призыва игрока. */
	public static final Item CLONE_PLAYER_SPAWN_EGG = Registry.register(
			Registries.ITEM,
			new Identifier(MOD_ID, "clone_player_spawn_egg"),
			new SpawnEggItem(CLONE_PLAYER, 0xEBB381, 0x3F3FA8, new FabricItemSettings())
	);

	/** Пульт: ПКМ открывает меню ввода Groq API-ключа. */
	public static final Item AI_CONFIG_ITEM = Registry.register(
			Registries.ITEM,
			new Identifier(MOD_ID, "ai_remote"),
			new AiConfigItem(new FabricItemSettings().maxCount(1))
	);

	@Override
	public void onInitialize() {
		FabricDefaultAttributeRegistry.register(CLONE_PLAYER, ClonePlayerEntity.createAttributes());
		AiNetworking.registerServer();

		ItemGroupEvents.modifyEntriesEvent(ItemGroups.SPAWN_EGGS)
				.register(entries -> entries.add(CLONE_PLAYER_SPAWN_EGG));
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS)
				.register(entries -> entries.add(AI_CONFIG_ITEM));

		// Чат игроков -> в «уши» ближайших NPC
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
			String text = message.getContent().getString();
			String name = sender.getGameProfile().getName();
			var world = sender.getServerWorld();
			for (ClonePlayerEntity npc : world.getEntitiesByClass(ClonePlayerEntity.class,
					new Box(sender.getBlockPos()).expand(32), e -> true)) {
				npc.getBrain2().hearChat(name, text);
			}
		});

		LOGGER.info("[PlayerEgg] Загружен. ИИ: {}", ServerAiSettings.hasKey() ? "ключ найден" : "ключ не задан");
	}
}
