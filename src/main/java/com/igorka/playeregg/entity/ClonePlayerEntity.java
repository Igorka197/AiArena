package com.igorka.playeregg.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * "Игрок", который ничего не делает: без ИИ, без движения, просто стоит на месте.
 */
public class ClonePlayerEntity extends PathAwareEntity {

	public ClonePlayerEntity(EntityType<? extends PathAwareEntity> type, World world) {
		super(type, world);
		// никаких целей ИИ не добавляем -> сущность неподвижна
		this.setAiDisabled(true);
		this.setPersistent();
	}

	public static DefaultAttributeContainer.Builder createAttributes() {
		return PathAwareEntity.createMobAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0D)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.0D)
				.add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 1.0D);
	}

	@Override
	protected void initGoals() {
		// пусто: стоит столбом
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	protected void pushAway(net.minecraft.entity.Entity entity) {
		// не толкается
	}

	@Override
	public boolean canBeLeashedBy(net.minecraft.entity.player.PlayerEntity player) {
		return false;
	}

	@Override
	public boolean isAiDisabled() {
		return true;
	}

	@Override
	protected @Nullable SoundEvent getAmbientSound() {
		return null;
	}

	@Override
	protected SoundEvent getHurtSound(DamageSource source) {
		return SoundEvents.ENTITY_PLAYER_HURT;
	}

	@Override
	protected SoundEvent getDeathSound() {
		return SoundEvents.ENTITY_PLAYER_DEATH;
	}
}
