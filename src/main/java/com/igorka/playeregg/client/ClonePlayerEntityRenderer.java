package com.igorka.playeregg.client;

import com.igorka.playeregg.entity.ClonePlayerEntity;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.feature.ArmorFeatureRenderer;
import net.minecraft.client.render.entity.feature.ElytraFeatureRenderer;
import net.minecraft.client.render.entity.feature.HeadFeatureRenderer;
import net.minecraft.client.render.entity.feature.HeldItemFeatureRenderer;
import net.minecraft.client.render.entity.model.ArmorEntityModel;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/** Рендер NPC ванильной моделью игрока со всеми стандартными анимациями. */
@Environment(EnvType.CLIENT)
public class ClonePlayerEntityRenderer
		extends MobEntityRenderer<ClonePlayerEntity, PlayerEntityModel<ClonePlayerEntity>> {

	private static final Identifier TEXTURE = new Identifier("minecraft", "textures/entity/player/wide/steve.png");

	public ClonePlayerEntityRenderer(EntityRendererFactory.Context ctx) {
		super(ctx, new PlayerEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER), false), 0.5F);

		this.addFeature(new HeldItemFeatureRenderer<>(this, ctx.getHeldItemRenderer()));
		this.addFeature(new ArmorFeatureRenderer<>(this,
				new ArmorEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_INNER_ARMOR)),
				new ArmorEntityModel<>(ctx.getPart(EntityModelLayers.PLAYER_OUTER_ARMOR)),
				ctx.getModelManager()));
		this.addFeature(new ElytraFeatureRenderer<>(this, ctx.getModelLoader()));
		this.addFeature(new HeadFeatureRenderer<>(this, ctx.getModelLoader(), ctx.getHeldItemRenderer()));
	}

	@Override
	public void render(ClonePlayerEntity entity, float yaw, float tickDelta, MatrixStack matrices,
	                   VertexConsumerProvider vertexConsumers, int light) {
		// ванильные флаги модели игрока
		PlayerEntityModel<ClonePlayerEntity> model = this.getModel();
		model.sneaking = entity.isSneakingNpc();
		model.hat.visible = true;
		model.jacket.visible = true;
		model.leftPants.visible = true;
		model.rightPants.visible = true;
		model.leftSleeve.visible = true;
		model.rightSleeve.visible = true;

		super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
	}

	@Override
	protected void setupTransforms(ClonePlayerEntity entity, MatrixStack matrices,
	                               float animationProgress, float bodyYaw, float tickDelta) {
		super.setupTransforms(entity, matrices, animationProgress, bodyYaw, tickDelta);
		// ванильное смещение при приседании
		if (entity.isSneakingNpc()) matrices.translate(0.0F, 0.125F, 0.0F);
	}

	@Override
	protected void scale(ClonePlayerEntity entity, MatrixStack matrices, float amount) {
		matrices.scale(0.9375F, 0.9375F, 0.9375F);   // как у игрока
	}

	@Override
	public Identifier getTexture(ClonePlayerEntity entity) {
		return TEXTURE;
	}
}
