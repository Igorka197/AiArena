package com.igorka.playeregg.ai;

import com.igorka.playeregg.entity.ClonePlayerEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * ИСПОЛНИТЕЛЬНЫЙ СЛОЙ (низкоуровневый).
 *
 * Вызывается КАЖДЫЙ тик и полностью автономен: навигация, бой, добыча и патруль
 * работают локально, без единого обращения к сети. LLM лишь переключает состояние
 * в NpcBlackboard, поэтому лаги API никогда не «замораживают» бота.
 */
public class NpcController {

	/** Радиус досягаемости рук игрока. */
	private static final double REACH = 4.5D;
	private static final double REACH_SQ = REACH * REACH;
	/** Кулдаун атаки ~0.6 с, как у меча в ванили. */
	private static final int ATTACK_COOLDOWN = 12;

	private final ClonePlayerEntity npc;
	private final NpcBlackboard bb;

	// --- локальные таймеры, чтобы не дёргать тяжёлые операции каждый тик ---
	private int attackCooldown;
	private int repathCooldown;
	private int scanCooldown;
	private int patrolCooldown;
	private int stuckTicks;
	private Vec3d lastPos = Vec3d.ZERO;

	// --- прогрессивная добыча блока (с анимацией замаха) ---
	private @Nullable BlockPos miningPos;
	private float miningProgress;
	private int swingTimer;

	public NpcController(ClonePlayerEntity npc, NpcBlackboard bb) {
		this.npc = npc;
		this.bb = bb;
	}

	/** Главный цикл. Дёшево по CPU: тяжёлые сканы вынесены в кулдауны. */
	public void tick() {
		if (attackCooldown > 0) attackCooldown--;
		if (repathCooldown > 0) repathCooldown--;
		if (scanCooldown > 0) scanCooldown--;
		if (patrolCooldown > 0) patrolCooldown--;
		if (swingTimer > 0) swingTimer--;

		// 1) РЕАКТИВНЫЙ СЛОЙ: угроза важнее любого приказа LLM.
		//    Работает мгновенно, не дожидаясь ответа сети.
		if (bb.state != NpcState.COMBAT) checkThreats();

		// 2) поза применяется всегда
		npc.setSneakingNpc(bb.wantSneak);
		npc.setSprinting(bb.wantSprint && !bb.wantSneak && bb.state != NpcState.IDLE);

		// 3) исполнение текущего состояния
		switch (bb.state) {
			case IDLE -> tickIdle();
			case GOTO -> tickGoto();
			case FOLLOW -> tickFollow();
			case PATROL -> tickPatrol();
			case COMBAT -> tickCombat();
			case MINE -> tickMine();
			case BUILD -> tickBuild();
			case SEEK_RESOURCE -> tickSeekResource();
		}

		detectStuck();
	}

	// ============================================================ СОСТОЯНИЯ

	private void tickIdle() {
		npc.getNavigation().stop();
		npc.freezeMovement();     // полная неподвижность, без «мобьего» дрейфа
	}

	private void tickGoto() {
		if (bb.moveTarget == null) { bb.setState(NpcState.IDLE); return; }

		if (npc.squaredDistanceTo(bb.moveTarget) < 2.25D) {   // пришли (1.5 блока)
			bb.moveTarget = null;
			npc.getNavigation().stop();
			bb.setState(NpcState.IDLE);
			return;
		}
		navigateTo(bb.moveTarget, 1.0D);
	}

	private void tickFollow() {
		PlayerEntity p = findPlayer(bb.followPlayer);
		if (p == null) { bb.setState(NpcState.IDLE); return; }

		npc.lookAtEntitySmooth(p);
		double d2 = npc.squaredDistanceTo(p);

		// Цель пересчитывается ЛОКАЛЬНО каждый тик — следование мгновенное.
		if (d2 > 12.0D) {                    // дальше ~3.5 блоков — догоняем
			navigateTo(p.getPos(), d2 > 100 ? 1.3D : 1.05D);
		} else if (d2 < 4.0D) {              // слишком близко — стоим
			npc.getNavigation().stop();
			npc.freezeMovement();
		}
	}

	private void tickPatrol() {
		if (bb.anchor == null) bb.anchor = npc.getPos();

		// Новая точка патруля выбирается локально раз в ~2 с, когда путь пройден.
		if (npc.getNavigation().isIdle() && patrolCooldown == 0) {
			patrolCooldown = 40;
			Vec3d t = net.minecraft.entity.ai.NoPenaltyTargeting.find(npc, 14, 7);
			if (t != null && bb.anchor.squaredDistanceTo(t) < 40 * 40) {
				npc.getNavigation().startMovingTo(t.x, t.y, t.z, 0.9D);
			} else if (bb.anchor.squaredDistanceTo(npc.getPos()) > 40 * 40) {
				navigateTo(bb.anchor, 1.0D);   // ушли слишком далеко — назад к якорю
			}
		}
	}

	/** Бой: преследование и удары мгновенно, каждый тик, без участия LLM. */
	private void tickCombat() {
		LivingEntity target = bb.combatTarget;
		if (target == null || !target.isAlive() || npc.squaredDistanceTo(target) > 40 * 40) {
			bb.combatTarget = null;
			bb.setState(NpcState.IDLE);
			return;
		}

		npc.lookAtEntitySmooth(target);
		double d2 = npc.squaredDistanceTo(target);

		if (d2 > REACH_SQ) {
			// вне досягаемости — бежим к цели (спринт в бою)
			npc.setSprinting(true);
			navigateTo(target.getPos(), 1.25D);
		} else {
			npc.getNavigation().stop();
			npc.setSprinting(false);
			if (attackCooldown == 0) {
				npc.swingHand(Hand.MAIN_HAND);       // ванильная анимация взмаха
				npc.tryAttack(target);
				attackCooldown = ATTACK_COOLDOWN;
			}
		}
	}

	/** Добыча блока с прогрессом и постоянной анимацией замаха. */
	private void tickMine() {
		BlockPos pos = bb.blockTarget;
		if (pos == null) { bb.setState(NpcState.IDLE); return; }

		World world = npc.getWorld();
		BlockState state = world.getBlockState(pos);
		if (state.isAir()) { finishMining(); return; }

		Vec3d center = Vec3d.ofCenter(pos);

		// далеко — сначала подходим (навигация, не телепорт)
		if (npc.squaredDistanceTo(center) > REACH_SQ) {
			navigateTo(center, 1.0D);
			miningProgress = 0;
			return;
		}

		npc.getNavigation().stop();
		npc.lookAtPosSmooth(center);

		if (!pos.equals(miningPos)) {          // начали новый блок
			miningPos = pos;
			miningProgress = 0;
		}

		// скорость копания по твёрдости блока — как в ванили
		float hardness = state.getHardness(world, pos);
		if (hardness < 0) { finishMining(); return; }        // бедрок
		float delta = hardness == 0 ? 1.0F
				: npc.getMainHandStack().getMiningSpeedMultiplier(state) / (hardness * 30F);
		miningProgress += Math.max(delta, 0.02F);

		// анимация замаха каждые 5 тиков + звук копания
		if (swingTimer == 0) {
			npc.swingHand(Hand.MAIN_HAND);
			swingTimer = 5;
			world.playSound(null, pos, state.getSoundGroup().getHitSound(),
					SoundCategory.BLOCKS, 0.25F, 0.7F);
		}
		// прогресс трещин виден всем клиентам
		world.setBlockBreakingInfo(npc.getId(), pos, (int) (miningProgress * 10) - 1);

		if (miningProgress >= 1.0F) {
			world.breakBlock(pos, true, npc);
			finishMining();
		}
	}

	private void finishMining() {
		if (miningPos != null) npc.getWorld().setBlockBreakingInfo(npc.getId(), miningPos, -1);
		miningPos = null;
		miningProgress = 0;
		bb.blockTarget = null;
		bb.setState(NpcState.IDLE);
	}

	private void tickBuild() {
		BlockPos pos = bb.blockTarget;
		if (pos == null) { bb.setState(NpcState.IDLE); return; }

		Vec3d center = Vec3d.ofCenter(pos);
		if (npc.squaredDistanceTo(center) > REACH_SQ) { navigateTo(center, 1.0D); return; }

		npc.getNavigation().stop();
		npc.lookAtPosSmooth(center);

		World world = npc.getWorld();
		if (world.getBlockState(pos).isReplaceable()) {
			var block = Blocks.STONE;
			if (bb.blockToPlace != null) {
				Identifier id = Identifier.tryParse(bb.blockToPlace.contains(":")
						? bb.blockToPlace : "minecraft:" + bb.blockToPlace);
				if (id != null && Registries.BLOCK.containsId(id)) block = Registries.BLOCK.get(id);
			}
			npc.swingHand(Hand.MAIN_HAND);          // анимация установки
			world.setBlockState(pos, block.getDefaultState());
			world.playSound(null, pos, block.getDefaultState().getSoundGroup().getPlaceSound(),
					SoundCategory.BLOCKS, 1.0F, 1.0F);
		}
		bb.blockTarget = null;
		bb.setState(NpcState.IDLE);
	}

	/** Локальный поиск ресурса: сканируем область раз в секунду, без сети. */
	private void tickSeekResource() {
		if (bb.resourceQuery == null) { bb.setState(NpcState.IDLE); return; }

		if (scanCooldown == 0) {
			scanCooldown = 20;
			BlockPos found = scanForBlock(bb.resourceQuery, 12);
			if (found != null) {
				bb.blockTarget = found;
				bb.setState(NpcState.MINE);
				return;
			}
			// не нашли — исследуем дальше
			bb.setState(NpcState.PATROL);
		}
	}

	/** Поиск ближайшего блока по имени в кубе radius. Дёшево: раз в 20 тиков. */
	private @Nullable BlockPos scanForBlock(String query, int radius) {
		World world = npc.getWorld();
		BlockPos origin = npc.getBlockPos();
		String q = query.toLowerCase().replace("minecraft:", "").trim();

		BlockPos best = null;
		double bestDist = Double.MAX_VALUE;

		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -4; dy <= 4; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					m.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
					String name = Registries.BLOCK.getId(world.getBlockState(m).getBlock()).getPath();
					if (!name.contains(q)) continue;
					double d = origin.getSquaredDistance(m);
					if (d < bestDist) { bestDist = d; best = m.toImmutable(); }
				}
			}
		}
		return best;
	}

	// ============================================================ УТИЛИТЫ

	/**
	 * Перемещение ТОЛЬКО через штатную навигацию Minecraft (EntityNavigation),
	 * поэтому бот плавно идёт по сетке блоков, обходит препятствия и не «плывёт».
	 */
	private void navigateTo(Vec3d target, double speed) {
		EntityNavigation nav = npc.getNavigation();

		// перестраиваем путь не чаще, чем раз в 10 тиков — экономия CPU
		if (nav.isIdle() || repathCooldown == 0) {
			repathCooldown = 10;
			Path path = nav.findPathTo(target.x, target.y, target.z, 0);
			if (path != null) {
				nav.startMovingAlong(path, speed);
			} else {
				// путь не найден: идём напрямую и прыгаем через препятствие
				npc.lookAtPosSmooth(target);
				npc.getMoveControl().moveTo(target.x, target.y, target.z, speed);
				if (npc.horizontalCollision) npc.requestJump();
			}
		}
		// автопрыжок на препятствие в 1 блок (как у игрока)
		if (npc.horizontalCollision && npc.isOnGround()) npc.requestJump();
	}

	/** Реактивная агрессия: враждебный моб рядом -> мгновенно в COMBAT. */
	private void checkThreats() {
		if (scanCooldown > 0) return;
		if (bb.state == NpcState.MINE || bb.state == NpcState.BUILD) return;

		LivingEntity threat = npc.getWorld().getEntitiesByClass(HostileEntity.class,
						new Box(npc.getBlockPos()).expand(8.0D), e -> e.isAlive())
				.stream()
				.min((a, b) -> Double.compare(npc.squaredDistanceTo(a), npc.squaredDistanceTo(b)))
				.orElse(null);

		if (threat != null) {
			bb.combatTarget = threat;
			bb.setState(NpcState.COMBAT);
		}
	}

	/** Антизастревание: если 3 с стоим на месте с активным путём — сбрасываем. */
	private void detectStuck() {
		if (bb.state == NpcState.IDLE) { stuckTicks = 0; return; }

		if (npc.getPos().squaredDistanceTo(lastPos) < 0.0025D) {
			if (++stuckTicks > 60) {
				stuckTicks = 0;
				npc.getNavigation().stop();
				npc.requestJump();
				repathCooldown = 0;
			}
		} else {
			stuckTicks = 0;
		}
		lastPos = npc.getPos();
	}

	private @Nullable PlayerEntity findPlayer(@Nullable String name) {
		if (name == null || name.isBlank()) return null;
		return npc.getWorld().getPlayers().stream()
				.filter(p -> p.getGameProfile().getName().equalsIgnoreCase(name.trim()))
				.findFirst().orElse(null);
	}
}
