package com.igorka.playeregg;

import com.igorka.playeregg.entity.ClonePlayerEntity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlayerEggMod implements ModInitializer {
	public static final String MOD_ID = "playeregg";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** Сущность "игрок-манекен" — просто стоит на месте. */
	public static final EntityType<ClonePlayerEntity> CLONE_PLAYER = Registry.register(
			Registries.ENTITY_TYPE,
			new Identifier(MOD_ID, "clone_player"),
			FabricEntityTypeBuilder.create(SpawnGroup.MISC, ClonePlayerEntity::new)
					.dimensions(EntityDimensions.fixed(0.6F, 1.8F))
					.trackRangeBlocks(64)
					.build()
	);

	/** Яйцо призыва игрока. */
	public static final Item CLONE_PLAYER_SPAWN_EGG = Registry.register(
			Registries.ITEM,
			new Identifier(MOD_ID, "clone_player_spawn_egg"),
			new SpawnEggItem(CLONE_PLAYER, 0xEBB381, 0x3F3FA8, new FabricItemSettings())
	);

	@Override
	public void onInitialize() {
		FabricDefaultAttributeRegistry.register(CLONE_PLAYER, ClonePlayerEntity.createAttributes());

		ItemGroupEvents.modifyEntriesEvent(ItemGroups.SPAWN_EGGS)
				.register(entries -> entries.add(CLONE_PLAYER_SPAWN_EGG));

		LOGGER.info("[PlayerEgg] Яйцо призыва игрока загружено.");
	}
}
