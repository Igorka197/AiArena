package com.igorka.playeregg.entity;

import com.igorka.playeregg.ai.AiAction;
import com.igorka.playeregg.ai.AiBrain;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.message.MessageType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * NPC-«игрок» под управлением ИИ (Groq).
 * Умеет: ходить к точке, следовать за игроком, бродить, прыгать, приседать, бегать, писать в чат.
 * Если ключа API нет — просто стоит на месте (поведение из версии 1.0).
 */
public class ClonePlayerEntity extends PathAwareEntity {

	private static final TrackedData<Boolean> SNEAKING =
			DataTracker.registerData(ClonePlayerEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<String> NPC_NAME =
			DataTracker.registerData(ClonePlayerEntity.class, TrackedDataHandlerRegistry.STRING);

	private static final EntityDimensionsHolder DIMS = new EntityDimensionsHolder();

	private final AiBrain brain = new AiBrain(this);

	/** текущая цель движения (null = стоим) */
	private @Nullable Vec3d moveTarget;
	private @Nullable String followTarget;
	private boolean wander;
	private boolean wantJump;
	private int jumpCooldown;

	public ClonePlayerEntity(EntityType<? extends PathAwareEntity> type, World world) {
		super(type, world);
		this.setPersistent();
		this.setCustomNameVisible(true);
	}

	public static DefaultAttributeContainer.Builder createAttributes() {
		return PathAwareEntity.createMobAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0D)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.28D)
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 48.0D)
				.add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.6D)
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 1.0D);
	}

	@Override
	protected void initDataTracker() {
		super.initDataTracker();
		this.dataTracker.startTracking(SNEAKING, false);
		this.dataTracker.startTracking(NPC_NAME, "Steve");
	}

	@Override
	protected void initGoals() {
		// Целей нет: всем управляет AiBrain.
	}

	// ---------------------------------------------------------------- ИИ API

	public void applyAction(AiAction a) {
		if (a == null) return;

		this.moveTarget = null;
		this.followTarget = null;
		this.wander = false;

		switch (a.action == null ? "stop" : a.action.toLowerCase()) {
			case "move_to" -> {
				if (a.x != null && a.z != null) {
					double y = a.y != null ? a.y : this.getY();
					this.moveTarget = new Vec3d(a.x, y, a.z);
				}
			}
			case "follow" -> this.followTarget = a.target;
			case "wander" -> this.wander = true;
			case "look_at" -> {
				PlayerEntity p = a.target == null ? null : this.getWorld().getPlayers().stream()
						.filter(pl -> pl.getGameProfile().getName().equalsIgnoreCase(a.target))
						.findFirst().orElse(null);
				if (p != null) this.getLookControl().lookAt(p, 30.0F, 30.0F);
			}
			default -> { /* stop */ }
		}

		this.setSneakingNpc(a.sneak);
		this.setSprinting(a.sprint && !a.sneak);
		if (a.jump) this.wantJump = true;

		if (a.say != null && !a.say.isBlank()) this.say(a.say);
	}

	public void say(String text) {
		if (this.getWorld().isClient) return;
		String name = this.getNpcName();
		Text msg = Text.literal("<" + name + "> " + text.trim());
		for (ServerPlayerEntity p : ((ServerWorld) this.getWorld()).getServer().getPlayerManager().getPlayerList()) {
			if (p.getWorld() == this.getWorld() && p.squaredDistanceTo(this) < 96 * 96) {
				p.sendMessage(msg, false);
			}
		}
	}

	public AiBrain getBrain2() {
		return brain;
	}

	public String getNpcName() {
		return this.dataTracker.get(NPC_NAME);
	}

	public void setNpcName(String name) {
		this.dataTracker.set(NPC_NAME, name);
		this.setCustomName(Text.literal(name));
	}

	// ------------------------------------------------------------- Приседание

	public void setSneakingNpc(boolean sneaking) {
		this.dataTracker.set(SNEAKING, sneaking);
		this.setPose(sneaking ? EntityPose.CROUCHING : EntityPose.STANDING);
	}

	public boolean isSneakingNpc() {
		return this.dataTracker.get(SNEAKING);
	}

	@Override
	public boolean isInSneakingPose() {
		return this.isSneakingNpc();
	}

	@Override
	public net.minecraft.entity.EntityDimensions getDimensions(EntityPose pose) {
		return pose == EntityPose.CROUCHING ? DIMS.crouching() : super.getDimensions(pose);
	}

	@Override
	protected float getActiveEyeHeight(EntityPose pose, net.minecraft.entity.EntityDimensions dims) {
		return pose == EntityPose.CROUCHING ? 1.27F : 1.62F;
	}

	// ------------------------------------------------------------------ Тик

	@Override
	public void tick() {
		super.tick();
		if (this.getWorld().isClient) return;

		if (jumpCooldown > 0) jumpCooldown--;

		brain.tick();

		// движение
		if (followTarget != null) {
			PlayerEntity p = this.getWorld().getPlayers().stream()
					.filter(pl -> pl.getGameProfile().getName().equalsIgnoreCase(followTarget))
					.findFirst().orElse(null);
			if (p != null) {
				this.getLookControl().lookAt(p, 30.0F, 30.0F);
				if (this.squaredDistanceTo(p) > 9.0D) {
					this.getNavigation().startMovingTo(p, this.isSneakingNpc() ? 0.5D : 1.0D);
				} else {
					this.getNavigation().stop();
				}
			}
		} else if (moveTarget != null) {
			if (this.squaredDistanceTo(moveTarget) < 1.5D) {
				moveTarget = null;
				this.getNavigation().stop();
			} else if (this.getNavigation().isIdle()) {
				this.getNavigation().startMovingTo(moveTarget.x, moveTarget.y, moveTarget.z,
						this.isSneakingNpc() ? 0.5D : 1.0D);
			}
		} else if (wander) {
			if (this.getNavigation().isIdle() && this.random.nextInt(40) == 0) {
				Vec3d t = net.minecraft.entity.ai.NoPenaltyTargeting.find(this, 10, 5);
				if (t != null) this.getNavigation().startMovingTo(t.x, t.y, t.z, 0.8D);
			}
		} else {
			this.getNavigation().stop();
		}

		// прыжок
		if (wantJump && this.isOnGround() && jumpCooldown == 0) {
			this.jump();
			this.wantJump = false;
			this.jumpCooldown = 10;
		}
	}

	@Override
	protected void jump() {
		super.jump();
	}

	// -------------------------------------------------------------- Прочее

	@Override
	public boolean canBeLeashedBy(PlayerEntity player) {
		return false;
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

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		nbt.putString("NpcName", this.getNpcName());
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		if (nbt.contains("NpcName")) this.setNpcName(nbt.getString("NpcName"));
	}

	/** Держатель размеров, чтобы не пересоздавать объект каждый кадр. */
	private static final class EntityDimensionsHolder {
		private final net.minecraft.entity.EntityDimensions crouching =
				net.minecraft.entity.EntityDimensions.changing(0.6F, 1.5F);

		net.minecraft.entity.EntityDimensions crouching() {
			return crouching;
		}
	}
}
