package com.igorka.playeregg.entity;

import com.igorka.playeregg.ai.*;
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
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * NPC-игрок.
 *
 * АРХИТЕКТУРА (разделение слоёв):
 *   AiBrain      — «что делать» (LLM, асинхронно, раз в N тиков)
 *        ↓ пишет в
 *   NpcBlackboard — общая доска целей и состояния
 *        ↓ читает каждый тик
 *   NpcController — «как делать» (навигация, бой, добыча — локально, 20 раз в секунду)
 *
 * Тело бота никогда не ждёт сеть: при лаге API он продолжает идти/драться по FSM.
 */
public class ClonePlayerEntity extends PathAwareEntity {

	private static final TrackedData<Boolean> SNEAKING =
			DataTracker.registerData(ClonePlayerEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
	private static final TrackedData<String> NPC_NAME =
			DataTracker.registerData(ClonePlayerEntity.class, TrackedDataHandlerRegistry.STRING);

	private static final EntityDimensions STANDING = EntityDimensions.changing(0.6F, 1.8F);
	private static final EntityDimensions CROUCHING = EntityDimensions.changing(0.6F, 1.5F);

	private final NpcBlackboard blackboard = new NpcBlackboard();
	private final NpcController controller = new NpcController(this, blackboard);
	private final AiBrain brain = new AiBrain(this, blackboard);

	private int jumpCooldown;
	private int pendingJump;

	public ClonePlayerEntity(EntityType<? extends PathAwareEntity> type, World world) {
		super(type, world);
		this.setPersistent();
		this.setCustomNameVisible(true);
		this.setStepHeight(0.6F);                       // как у игрока
		this.getNavigation().setCanSwim(true);
	}

	public static DefaultAttributeContainer.Builder createAttributes() {
		return MobEntity.createMobAttributes()
				.add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0D)
				.add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.1D)
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
		// Ванильные Goal не используются: всё поведение в NpcController.
	}

	// ==================================================== ГЛАВНЫЙ ЦИКЛ

	@Override
	public void tick() {
		super.tick();
		if (this.getWorld().isClient) return;

		if (jumpCooldown > 0) jumpCooldown--;

		// 1) Планировщик: неблокирующий, обычно просто выходит.
		brain.tick();
		// 2) Исполнитель: работает каждый тик автономно.
		controller.tick();

		// 3) Прыжок с ванильной физикой
		if (pendingJump > 0) {
			pendingJump--;
			if (this.isOnGround() && jumpCooldown == 0) {
				this.getJumpControl().setActive();
				this.jump();
				jumpCooldown = 8;
				pendingJump = 0;
			}
		}
	}

	// ============================================ ПРИМЕНЕНИЕ РЕШЕНИЯ LLM

	/** Переводит решение LLM в состояние FSM. Вызывается только в потоке сервера. */
	public void applyDecision(AiAction a) {
		if (a == null) return;
		String act = a.action == null ? "stop" : a.action.toLowerCase().trim();

		if (a.sneak != null) blackboard.wantSneak = a.sneak;
		if (a.sprint != null) blackboard.wantSprint = a.sprint;
		if (AiAction.on(a.jump)) requestJump();

		switch (act) {
			case "move_to", "goto" -> {
				blackboard.clearTargets();
				if (a.x != null && a.z != null) {
					blackboard.moveTarget = new Vec3d(a.x, a.y != null ? a.y : this.getY(), a.z);
					blackboard.setState(NpcState.GOTO);
				}
			}
			case "follow" -> {
				blackboard.clearTargets();
				blackboard.followPlayer = a.target;
				blackboard.setState(NpcState.FOLLOW);
			}
			case "patrol", "wander", "explore" -> {
				blackboard.clearTargets();
				blackboard.anchor = this.getPos();
				blackboard.setState(NpcState.PATROL);
			}
			case "attack" -> {
				LivingEntity victim = resolveTarget(a.target);
				if (victim != null) {
					blackboard.clearTargets();
					blackboard.combatTarget = victim;
					blackboard.setState(NpcState.COMBAT);
				}
			}
			case "mine", "break_block" -> {
				if (a.x != null && a.y != null && a.z != null) {
					blackboard.clearTargets();
					blackboard.blockTarget = BlockPos.ofFloored(a.x, a.y, a.z);
					blackboard.setState(NpcState.MINE);
				}
			}
			case "build", "place_block" -> {
				if (a.x != null && a.y != null && a.z != null) {
					blackboard.clearTargets();
					blackboard.blockTarget = BlockPos.ofFloored(a.x, a.y, a.z);
					blackboard.blockToPlace = a.block;
					blackboard.setState(NpcState.BUILD);
				}
			}
			case "seek", "seek_resource", "find" -> {
				blackboard.clearTargets();
				blackboard.resourceQuery = a.resource != null ? a.resource : a.block;
				blackboard.setState(NpcState.SEEK_RESOURCE);
			}
			case "equip", "hold" -> equip(a.item);
			case "drop" -> {
				ItemStack st = this.getMainHandStack();
				if (!st.isEmpty()) {
					this.dropStack(st.copy());
					this.equipStack(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
				}
			}
			case "swing", "use_item", "use" -> this.swingHand(Hand.MAIN_HAND);
			case "look_at" -> {
				PlayerEntity p = findPlayer(a.target);
				if (p != null) lookAtEntitySmooth(p);
			}
			default -> {   // stop / idle / wait
				blackboard.clearTargets();
				blackboard.setState(NpcState.IDLE);
			}
		}

		if (a.say != null && !a.say.isBlank()) say(a.say);
	}

	private @Nullable LivingEntity resolveTarget(@Nullable String name) {
		if (name == null || name.isBlank()) return null;
		PlayerEntity p = findPlayer(name);
		if (p != null) return p;
		String q = name.toLowerCase().trim();
		return this.getWorld().getEntitiesByClass(LivingEntity.class,
						this.getBoundingBox().expand(24),
						e -> e != this && e.getType().getUntranslatedName().toLowerCase().contains(q))
				.stream().min((x, y) -> Double.compare(squaredDistanceTo(x), squaredDistanceTo(y)))
				.orElse(null);
	}

	private @Nullable PlayerEntity findPlayer(@Nullable String name) {
		if (name == null || name.isBlank()) return null;
		return this.getWorld().getPlayers().stream()
				.filter(p -> p.getGameProfile().getName().equalsIgnoreCase(name.trim()))
				.findFirst().orElse(null);
	}

	private void equip(@Nullable String itemId) {
		if (itemId == null || itemId.isBlank()) return;
		Identifier id = Identifier.tryParse(itemId.contains(":") ? itemId : "minecraft:" + itemId.trim());
		if (id == null || !Registries.ITEM.containsId(id)) return;
		this.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Registries.ITEM.get(id)));
		this.swingHand(Hand.MAIN_HAND);              // анимация смены предмета
	}

	// ============================================ API ДЛЯ КОНТРОЛЛЕРА

	/** Запрос прыжка: исполнится, когда бот коснётся земли. */
	public void requestJump() {
		this.pendingJump = 4;
	}

	/** Полная остановка: гасим скорость и вращение, гравитацию оставляем. */
	public void freezeMovement() {
		Vec3d v = this.getVelocity();
		this.setVelocity(0.0D, Math.min(v.y, 0.0D), 0.0D);
		this.setForwardSpeed(0.0F);
		this.setSidewaysSpeed(0.0F);
		this.setUpwardSpeed(0.0F);
		this.setMovementSpeed(0.0F);
		this.setBodyYaw(this.getYaw());
		this.setHeadYaw(this.getYaw());
	}

	/** Плавный поворот к сущности — без рывков камеры, чтобы не было визуальных багов. */
	public void lookAtEntitySmooth(Entity e) {
		lookAtPosSmooth(new Vec3d(e.getX(), e.getEyeY(), e.getZ()));
	}

	/** Плавный поворот к точке: ограничиваем скорость поворота, как у ванильного LookControl. */
	public void lookAtPosSmooth(Vec3d v) {
		double dx = v.x - this.getX();
		double dz = v.z - this.getZ();
		double dy = v.y - this.getEyeY();
		double horiz = Math.sqrt(dx * dx + dz * dz);

		float targetYaw = (float) (MathHelper.atan2(dz, dx) * 57.2957795 - 90.0);
		float targetPitch = (float) (-(MathHelper.atan2(dy, horiz) * 57.2957795));

		this.setYaw(approachAngle(this.getYaw(), targetYaw, 20.0F));
		this.setHeadYaw(this.getYaw());
		this.setBodyYaw(this.getYaw());
		this.setPitch(approachAngle(this.getPitch(), targetPitch, 20.0F));
	}

	private static float approachAngle(float from, float to, float maxDelta) {
		float diff = MathHelper.wrapDegrees(to - from);
		return from + MathHelper.clamp(diff, -maxDelta, maxDelta);
	}

	public void say(String text) {
		if (this.getWorld().isClient) return;
		Text msg = Text.literal("<" + getNpcName() + "> " + text.trim());
		for (ServerPlayerEntity p : ((ServerWorld) this.getWorld()).getServer().getPlayerManager().getPlayerList()) {
			if (p.getWorld() == this.getWorld() && p.squaredDistanceTo(this) < 96 * 96) p.sendMessage(msg, false);
		}
	}

	public AiBrain getBrain2() { return brain; }

	public NpcBlackboard getBlackboard() { return blackboard; }

	/** Получил урон -> мгновенно в бой, без участия LLM. */
	@Override
	public boolean damage(DamageSource source, float amount) {
		boolean hurt = super.damage(source, amount);
		if (hurt && !this.getWorld().isClient
				&& source.getAttacker() instanceof LivingEntity attacker && attacker != this) {
			blackboard.combatTarget = attacker;
			blackboard.setState(NpcState.COMBAT);
		}
		return hurt;
	}

	public String getNpcName() { return this.dataTracker.get(NPC_NAME); }

	public void setNpcName(String name) {
		this.dataTracker.set(NPC_NAME, name);
		this.setCustomName(Text.literal(name));
	}

	// ------------------------------------------------------- Приседание

	public void setSneakingNpc(boolean sneaking) {
		if (this.dataTracker.get(SNEAKING) == sneaking) return;
		this.dataTracker.set(SNEAKING, sneaking);
		this.setPose(sneaking ? EntityPose.CROUCHING : EntityPose.STANDING);
		this.calculateDimensions();
	}

	public boolean isSneakingNpc() { return this.dataTracker.get(SNEAKING); }

	@Override
	public boolean isInSneakingPose() { return isSneakingNpc(); }

	@Override
	public boolean isSneaking() { return isSneakingNpc(); }

	@Override
	public EntityDimensions getDimensions(EntityPose pose) {
		return pose == EntityPose.CROUCHING ? CROUCHING : STANDING;
	}

	@Override
	protected float getActiveEyeHeight(EntityPose pose, EntityDimensions dims) {
		return pose == EntityPose.CROUCHING ? 1.27F : 1.62F;
	}

	// -------------------------------------------------------------- Прочее

	@Override
	public boolean canBeLeashedBy(PlayerEntity player) { return false; }

	@Override
	public boolean cannotDespawn() { return true; }

	@Override
	public void checkDespawn() { }

	@Override
	protected @Nullable SoundEvent getAmbientSound() { return null; }

	@Override
	protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.ENTITY_PLAYER_HURT; }

	@Override
	protected SoundEvent getDeathSound() { return SoundEvents.ENTITY_PLAYER_DEATH; }

	@Override
	public void writeCustomDataToNbt(NbtCompound nbt) {
		super.writeCustomDataToNbt(nbt);
		nbt.putString("NpcName", getNpcName());
	}

	@Override
	public void readCustomDataFromNbt(NbtCompound nbt) {
		super.readCustomDataFromNbt(nbt);
		if (nbt.contains("NpcName")) setNpcName(nbt.getString("NpcName"));
	}
}
