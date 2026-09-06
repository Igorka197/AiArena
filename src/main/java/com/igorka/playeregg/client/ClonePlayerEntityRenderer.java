package com.igorka.playeregg.client;

import com.igorka.playeregg.entity.ClonePlayerEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
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
	protected void setupTransforms(ClonePlayerEntity entity, MatrixStack matrices,
	                               float animationProgress, float bodyYaw, float tickDelta) {
		super.setupTransforms(entity, matrices, animationProgress, bodyYaw, tickDelta);
		// визуальное приседание
		this.model.sneaking = entity.isSneakingNpc();
		if (entity.isSneakingNpc()) {
			matrices.translate(0.0F, 0.125F, 0.0F);
		}
	}

	@Override
	public Identifier getTexture(ClonePlayerEntity entity) {
		return TEXTURE;
	}
}
