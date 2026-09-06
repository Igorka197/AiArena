package com.igorka.playeregg.client;

import com.igorka.playeregg.PlayerEggMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class PlayerEggClientMod implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(PlayerEggMod.CLONE_PLAYER, ClonePlayerEntityRenderer::new);
	}
}
