package com.igorka.playeregg.net;

import com.igorka.playeregg.PlayerEggMod;
import com.igorka.playeregg.ai.ServerAiSettings;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/** Канал: клиентское меню -> сервер (настройки Groq). */
public final class AiNetworking {
	public static final Identifier SETTINGS = new Identifier(PlayerEggMod.MOD_ID, "ai_settings");

	private AiNetworking() {}

	public static void registerServer() {
		ServerPlayNetworking.registerGlobalReceiver(SETTINGS, (server, player, handler, buf, sender) -> {
			String key = buf.readString(256);
			String model = buf.readString(128);
			String personality = buf.readString(512);
			int interval = buf.readVarInt();
			boolean enabled = buf.readBoolean();

			server.execute(() -> {
				if (!player.hasPermissionLevel(2) && server.isDedicated()) {
					player.sendMessage(Text.literal("Нужны права оператора для настройки ИИ.")
							.formatted(Formatting.RED), false);
					return;
				}
				ServerAiSettings.update(key, model, personality, interval, enabled);
				player.sendMessage(Text.literal("[PlayerEgg] Настройки ИИ сохранены. Модель: " + model)
						.formatted(Formatting.GREEN), false);
			});
		});
	}
}
