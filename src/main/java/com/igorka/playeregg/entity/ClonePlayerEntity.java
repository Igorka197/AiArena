package com.igorka.playeregg.entity;

import com.igorka.playeregg.ai.AiAction;
import com.igorka.playeregg.ai.AiBrain;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.*;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * NPC-«игрок» под управлением ИИ.
 * Без приказа стоит АБСОЛЮТНО неподвижно (без «мобьего» дрейфа и вращения головы).
 * Умеет: ходить, следовать, прыгать, приседать, бегать, ломать и ставить блоки,
 * атаковать, махать рукой, брать предметы в руку, писать в чат.
 */
public class ClonePlayerEntity extends PathAwareEntity {

	private static final TrackedData<Boolean> SNEAKING =
			DataTracker.registerData(ClonePlayerEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<String> NPC_NAME =
			DataTracker.registerData(ClonePlayerEntity.class, TrackedDataHandlerRegistry.STRING);

	private static final EntityDimensions STANDING = EntityDimensions.changing(0.6F, 1.8F);
	private static final EntityDimensions CROUCHING = EntityDimensions.changing(0.6F, 1.5F);

	private final AiBrain brain = new AiBrain(this);

	private @Nullable Vec3d moveTarget;
	private @Nullable String followTarget;
	private boolean wander;
	private int wantJumpTicks;
	private int jumpCooldown;
	private int breakCooldown;
	private int attackCooldown;

	public ClonePlayerEntity(EntityType<? extends PathAwareEntity> type, World world) {
		super(type, world);
		this.setPersistent();
		this.setCustomNameVisible(true);
		this.setStepHeight(0.6F);          // как у игрока: заходит на полблока
		this.getNavigation().setCanSwim(true);
	}

	public static DefaultAttributeContainer.Builder createAttributes() {
		return MobEntity.createMobAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0D)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.1D)   // как у игрока
				.add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0D)
				.add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 0.0D)
				.add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 2.0D);
	}

	@Override
	protected void initDataTracker() {
		super.initDataTracker();
		this.dataTracker.startTracking(SNEAKING, false);
		this.dataTracker.startTracking(NPC_NAME, "Steve");
	}

	@Override
	protected void initGoals() {
		// Никаких ванильных целей: всё решает ИИ.
	}

	// ============================================================ ИСПОЛНЕНИЕ

	public void applyAction(AiAction a) {
		if (a == null) return;
		String act = a.action == null ? "stop" : a.action.toLowerCase().trim();

		// флаги позы применяем всегда
		if (a.sneak != null) this.setSneakingNpc(a.sneak);
		if (a.sprint != null) this.setSprinting(a.sprint && !this.isSneakingNpc());
		if (a.flag(a.jump)) this.wantJumpTicks = 4;

		switch (act) {
			case "move_to", "goto", "jump_to" -> {
				clearMovement();
				if (a.x != null && a.z != null) {
					double ty = a.y != null ? a.y : this.getY();
					this.moveTarget = new Vec3d(a.x, ty, a.z);
					if (act.equals("jump_to")) this.wantJumpTicks = 20;
				}
			}
			case "follow" -> {
				clearMovement();
				this.followTarget = a.target;
			}
			case "wander", "explore" -> {
				clearMovement();
				this.wander = true;
			}
			case "stop", "idle", "wait" -> {
				clearMovement();
				this.getNavigation().stop();
			}
			case "look_at" -> lookAtName(a.target);
			case "break_block", "mine" -> breakBlock(a);
			case "place_block", "build" -> placeBlock(a);
			case "attack" -> attackTarget(a.target);
			case "equip", "hold" -> equip(a.item);
			case "use_item", "use" -> {
				this.swingHand(Hand.MAIN_HAND);
			}
			case "drop" -> {
				ItemStack st = this.getMainHandStack();
				if (!st.isEmpty()) {
					this.dropStack(st.copy());
					this.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
				}
			}
			case "swing" -> this.swingHand(Hand.MAIN_HAND);
			default -> { /* неизвестное действие — сохраняем текущее поведение */ }
		}

		if (a.say != null && !a.say.isBlank()) this.say(a.say);

		// цепочка действий
		if (a.then != null) for (AiAction next : a.then) applyAction(next);
	}

	private void clearMovement() {
		this.moveTarget = null;
		this.followTarget = null;
		this.wander = false;
	}

	private @Nullable PlayerEntity findPlayer(@Nullable String name) {
		if (name == null || name.isBlank()) return null;
		return this.getWorld().getPlayers().stream()
				.filter(p -> p.getGameProfile().getName().equalsIgnoreCase(name.trim()))
				.findFirst().orElse(null);
	}

	private void lookAtName(@Nullable String name) {
		PlayerEntity p = findPlayer(name);
		if (p != null) this.lookAtEntity(p);
	}

	private void lookAtEntity(Entity e) {
		double dx = e.getX() - this.getX();
		double dz = e.getZ() - this.getZ();
		double dy = e.getEyeY() - this.getEyeY();
		float yaw = (float) (Math.atan2(dz, dx) * 57.2957795 - 90.0);
		float pitch = (float) (-(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 57.2957795));
		this.setYaw(yaw);
		this.setHeadYaw(yaw);
		this.setBodyYaw(yaw);
		this.setPitch(pitch);
	}

	private void lookAtPos(Vec3d v) {
		double dx = v.x - this.getX();
		double dz = v.z - this.getZ();
		double dy = v.y - this.getEyeY();
		float yaw = (float) (Math.atan2(dz, dx) * 57.2957795 - 90.0);
		this.setYaw(yaw);
		this.setHeadYaw(yaw);
		this.setBodyYaw(yaw);
		this.setPitch((float) (-(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 57.2957795)));
	}

	// ------------------------------------------------------- Блоки и бой

	private void breakBlock(AiAction a) {
		if (a.x == null || a.y == null || a.z == null) return;
		if (breakCooldown > 0) return;
		BlockPos pos = BlockPos.ofFloored(a.x, a.y, a.z);
		if (this.squaredDistanceTo(Vec3d.ofCenter(pos)) > 36) {  // радиус ~6 блоков
			say("Слишком далеко, подойду ближе.");
			this.moveTarget = Vec3d.ofCenter(pos);
			return;
		}
		BlockState st = this.getWorld().getBlockState(pos);
		if (st.isAir() || st.getHardness(this.getWorld(), pos) < 0) return;

		lookAtPos(Vec3d.ofCenter(pos));
		this.swingHand(Hand.MAIN_HAND);
		this.getWorld().breakBlock(pos, true, this);
		breakCooldown = 8;
	}

	private void placeBlock(AiAction a) {
		if (a.x == null || a.y == null || a.z == null) return;
		BlockPos pos = BlockPos.ofFloored(a.x, a.y, a.z);
		if (this.squaredDistanceTo(Vec3d.ofCenter(pos)) > 36) {
			say("Далековато, иду туда.");
			this.moveTarget = Vec3d.ofCenter(pos);
			return;
		}
		if (!this.getWorld().getBlockState(pos).isReplaceable()) return;

		Block block = Blocks.STONE;
		if (a.block != null && !a.block.isBlank()) {
			Identifier id = Identifier.tryParse(a.block.contains(":") ? a.block : "minecraft:" + a.block.trim());
			if (id != null && Registries.BLOCK.containsId(id)) block = Registries.BLOCK.get(id);
		}
		lookAtPos(Vec3d.ofCenter(pos));
		this.swingHand(Hand.MAIN_HAND);
		this.getWorld().setBlockState(pos, block.getDefaultState());
		this.getWorld().playSound(null, pos, block.getDefaultState().getSoundGroup().getPlaceSound(),
				net.minecraft.sound.SoundCategory.BLOCKS, 1.0F, 1.0F);
	}

	private void attackTarget(@Nullable String targetName) {
		if (attackCooldown > 0 || targetName == null) return;

		LivingEntity victim = findPlayer(targetName);
		if (victim == null) {
			// ищем моба по типу
			victim = this.getWorld().getEntitiesByClass(LivingEntity.class,
							this.getBoundingBox().expand(8),
							e -> e != this && e.getType().getUntranslatedName()
									.toLowerCase().contains(targetName.toLowerCase().trim()))
					.stream().min((x, y) -> Double.compare(this.squaredDistanceTo(x), this.squaredDistanceTo(y)))
					.orElse(null);
		}
		if (victim == null) return;

		lookAtEntity(victim);
		if (this.squaredDistanceTo(victim) > 12.0) {
			this.moveTarget = victim.getPos();
			return;
		}
		this.swingHand(Hand.MAIN_HAND);
		this.tryAttack(victim);
		attackCooldown = 12;
	}

	private void equip(@Nullable String itemId) {
		if (itemId == null || itemId.isBlank()) return;
		Identifier id = Identifier.tryParse(itemId.contains(":") ? itemId : "minecraft:" + itemId.trim());
		if (id == null || !Registries.ITEM.containsId(id)) return;
		Item item = Registries.ITEM.get(id);
		this.equipStack(EquipmentSlot.MAINHAND, new ItemStack(item));
	}

	// -------------------------------------------------------------- Чат

	public void say(String text) {
		if (this.getWorld().isClient) return;
		Text msg = Text.literal("<" + this.getNpcName() + "> " + text.trim());
		for (ServerPlayerEntity p : ((ServerWorld) this.getWorld()).getServer().getPlayerManager().getPlayerList()) {
			if (p.getWorld() == this.getWorld() && p.squaredDistanceTo(this) < 96 * 96) p.sendMessage(msg, false);
		}
	}

	public AiBrain getBrain2() { return brain; }

	public String getNpcName() { return this.dataTracker.get(NPC_NAME); }

	public void setNpcName(String name) {
		this.dataTracker.set(NPC_NAME, name);
		this.setCustomName(Text.literal(name));
	}

	public boolean isBusyMoving() {
		return moveTarget != null || followTarget != null || wander;
	}

	public String currentTask() {
		if (followTarget != null) return "follow " + followTarget;
		if (moveTarget != null) return "move_to " + (int) moveTarget.x + "," + (int) moveTarget.y + "," + (int) moveTarget.z;
		if (wander) return "wander";
		return "stop";
	}

	// -------------------------------------------------------- Приседание

	public void setSneakingNpc(boolean sneaking) {
		if (this.dataTracker.get(SNEAKING) == sneaking) return;
		this.dataTracker.set(SNEAKING, sneaking);
		this.setPose(sneaking ? EntityPose.CROUCHING : EntityPose.STANDING);
		this.calculateDimensions();
	}

	public boolean isSneakingNpc() { return this.dataTracker.get(SNEAKING); }

	@Override
	public boolean isInSneakingPose() { return this.isSneakingNpc(); }

	@Override
	public boolean isSneaking() { return this.isSneakingNpc(); }

	@Override
	public EntityDimensions getDimensions(EntityPose pose) {
		return pose == EntityPose.CROUCHING ? CROUCHING : STANDING;
	}

	@Override
	protected float getActiveEyeHeight(EntityPose pose, EntityDimensions dims) {
		return pose == EntityPose.CROUCHING ? 1.27F : 1.62F;
	}

	// ------------------------------------------------------------- Тик

	@Override
	public void tick() {
		super.tick();
		if (this.getWorld().isClient) return;

		if (jumpCooldown > 0) jumpCooldown--;
		if (breakCooldown > 0) breakCooldown--;
		if (attackCooldown > 0) attackCooldown--;

		brain.tick();

		if (followTarget != null) {
			PlayerEntity p = findPlayer(followTarget);
			if (p != null) {
				lookAtEntity(p);
				double d2 = this.squaredDistanceTo(p);
				if (d2 > 9.0) {
					this.getNavigation().startMovingTo(p, this.isSneakingNpc() ? 0.6D : 1.15D);
				} else {
					this.getNavigation().stop();
					freeze();
				}
			}
		} else if (moveTarget != null) {
			if (this.squaredDistanceTo(moveTarget) < 2.0D) {
				moveTarget = null;
				this.getNavigation().stop();
				freeze();
			} else {
				if (this.getNavigation().isIdle()) {
					boolean ok = this.getNavigation().startMovingTo(
							moveTarget.x, moveTarget.y, moveTarget.z, this.isSneakingNpc() ? 0.6D : 1.0D);
					if (!ok) {                       // путь не найден — идём напролом и прыгаем
						lookAtPos(moveTarget);
						this.getMoveControl().moveTo(moveTarget.x, moveTarget.y, moveTarget.z, 1.0D);
						if (this.horizontalCollision) this.wantJumpTicks = 4;
					}
				}
				if (this.horizontalCollision && this.isOnGround()) this.wantJumpTicks = 4;
			}
		} else if (wander) {
			if (this.getNavigation().isIdle() && this.random.nextInt(60) == 0) {
				Vec3d t = net.minecraft.entity.ai.NoPenaltyTargeting.find(this, 12, 6);
				if (t != null) this.getNavigation().startMovingTo(t.x, t.y, t.z, 0.9D);
			}
		} else {
			// ПОЛНАЯ неподвижность: никакого «мобьего» дрейфа
			this.getNavigation().stop();
			freeze();
		}

		// прыжок как у игрока
		if (wantJumpTicks > 0) {
			wantJumpTicks--;
			if (this.isOnGround() && jumpCooldown == 0) {
				this.getJumpControl().setActive();
				this.jump();
				jumpCooldown = 8;
				wantJumpTicks = 0;
			}
		}
	}

	/** Гасим горизонтальную скорость и вращение, оставляя гравитацию. */
	private void freeze() {
		Vec3d v = this.getVelocity();
		this.setVelocity(0.0D, Math.min(v.y, 0.0D), 0.0D);
		this.setForwardSpeed(0.0F);
		this.setSidewaysSpeed(0.0F);
		this.setUpwardSpeed(0.0F);
		this.setMovementSpeed(0.0F);
		this.setBodyYaw(this.getYaw());
		this.setHeadYaw(this.getYaw());
	}

	/** Мобы «озираются» через LookControl — отключаем, когда стоим без задачи. */
	@Override
	public void tickMovement() {
		super.tickMovement();
		if (!this.getWorld().isClient && !isBusyMoving()) {
			this.setForwardSpeed(0.0F);
			this.setSidewaysSpeed(0.0F);
		}
	}

	@Override
	public boolean canBeLeashedBy(PlayerEntity player) { return false; }

	@Override
	public boolean cannotDespawn() { return true; }

	@Override
	public void checkDespawn() { /* никогда не исчезает */ }

	@Override
	protected @Nullable SoundEvent getAmbientSound() { return null; }

	@Override
	protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.ENTITY_PLAYER_HURT; }

	@Override
	protected SoundEvent getDeathSound() { return SoundEvents.ENTITY_PLAYER_DEATH; }

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
}
