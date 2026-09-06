package com.igorka.playeregg.client;

import com.igorka.playeregg.PlayerEggMod;
import com.igorka.playeregg.ai.AiConfig;
import com.igorka.playeregg.net.AiNetworking;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.TypedActionResult;
import org.lwjgl.glfw.GLFW;

public class PlayerEggClientMod implements ClientModInitializer {

	private static KeyBinding openConfigKey;

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(PlayerEggMod.CLONE_PLAYER, ClonePlayerEntityRenderer::new);

		openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.playeregg.open_config",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_G,
				"key.categories.playeregg"
		));

		// Клавиша G — открыть меню
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openConfigKey.wasPressed()) {
				client.setScreen(new AiConfigScreen(client.currentScreen));
			}
		});

		// ПКМ пультом ИИ — открыть меню; ПКМ яйцом без ключа — тоже открыть меню (перед спавном)
		UseItemCallback.EVENT.register((player, world, hand) -> {
			var stack = player.getStackInHand(hand);
			if (!world.isClient) return TypedActionResult.pass(stack);

			boolean isConfigItem = stack.isOf(PlayerEggMod.AI_CONFIG_ITEM);
			boolean isEggWithoutKey = stack.isOf(PlayerEggMod.CLONE_PLAYER_SPAWN_EGG) && !AiConfig.get().hasKey();

			if (isConfigItem || isEggWithoutKey) {
				var client = net.minecraft.client.MinecraftClient.getInstance();
				client.execute(() -> client.setScreen(new AiConfigScreen(null)));
				return TypedActionResult.success(stack, true);
			}
			return TypedActionResult.pass(stack);
		});

		// При входе в мир отправляем сохранённые настройки на сервер
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			AiConfig cfg = AiConfig.get();
			if (!cfg.hasKey()) return;
			PacketByteBuf buf = PacketByteBufs.create();
			buf.writeString(cfg.apiKey, 256);
			buf.writeString(cfg.model, 128);
			buf.writeString(cfg.personality, 512);
			buf.writeVarInt(cfg.thinkIntervalTicks);
			buf.writeBoolean(cfg.enabled);
			ClientPlayNetworking.send(AiNetworking.SETTINGS, buf);
		});
	}
}
