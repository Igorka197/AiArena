package com.igorka.playeregg.net;

import com.igorka.playeregg.PlayerEggMod;
import com.igorka.playeregg.ai.ServerAiSettings;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/** Канал: клиентское меню -> сервер. */
public final class AiNetworking {
	public static final Identifier SETTINGS = new Identifier(PlayerEggMod.MOD_ID, "ai_settings");

	private AiNetworking() {}

	public static void registerServer() {
		ServerPlayNetworking.registerGlobalReceiver(SETTINGS, (server, player, handler, buf, sender) -> {
			String key = buf.readString(256);
			String personality = buf.readString(512);
			int interval = buf.readVarInt();
			boolean enabled = buf.readBoolean();
			boolean debug = buf.readBoolean();
			String providerName = buf.readString(32);

			server.execute(() -> {
				if (server.isDedicated() && !player.hasPermissionLevel(2)) {
					player.sendMessage(Text.literal("Нужны права оператора для настройки ИИ.")
							.formatted(Formatting.RED), false);
					return;
				}
				com.igorka.playeregg.ai.AiProvider prov;
				try { prov = com.igorka.playeregg.ai.AiProvider.valueOf(providerName); }
				catch (Exception e) { prov = com.igorka.playeregg.ai.AiProvider.CEREBRAS; }
				ServerAiSettings.update(key, personality, interval, enabled, debug, prov);
				player.sendMessage(Text.literal("[PlayerEgg] Настройки ИИ применены ("
						+ ServerAiSettings.model() + ", интервал " + interval + " тиков).")
						.formatted(Formatting.GREEN), false);
			});
		});
	}
}
