package com.igorka.playeregg.client;

import com.igorka.playeregg.entity.ClonePlayerEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.util.Identifier;

@Environment(EnvType.CLIENT)
public class ClonePlayerEntityRenderer
		extends MobEntityRenderer<ClonePlayerEntity, PlayerEntityModel<ClonePlayerEntity>> {

	private static final Identifier TEXTURE = new Identifier("minecraft", "textures/entity/player/wide/steve.png");

	public ClonePlayerEntityRenderer(EntityRendererFactory.Context ctx) {
		super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5F);
		this.addFeature(new HeldItemFeatureRenderer<>(this, ctx.getHeldItemRenderer()));
	}

	@Override
	public Identifier getTexture(ClonePlayerEntity entity) {
		return TEXTURE;
	}
}
