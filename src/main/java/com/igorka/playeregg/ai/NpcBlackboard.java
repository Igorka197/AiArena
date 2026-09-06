package com.igorka.playeregg.ai;

import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * «Доска задач» — общая память между LLM-слоем и исполнительным слоем.
 * LLM пишет сюда цель, tick() её исполняет. Разделение решений и исполнения.
 */
public class NpcBlackboard {
	public volatile NpcState state = NpcState.IDLE;

	public @Nullable Vec3d moveTarget;
	public @Nullable String followPlayer;
	public @Nullable BlockPos blockTarget;
	public @Nullable String blockToPlace;
	public @Nullable LivingEntity combatTarget;
	public @Nullable String resourceQuery;

	/** Флаги позы держим отдельно: они не сбрасываются сменой состояния. */
	public volatile boolean wantSneak;
	public volatile boolean wantSprint;

	/** Дом-точка для патрулирования. */
	public @Nullable Vec3d anchor;

	/** Режим скорости для текущей цели (ходьба / бег / крадучись). */
	public volatile MoveSpeed speed = MoveSpeed.WALK;

	/** Сущность-цель для преследования (моб или игрок), если задана — идём за ней. */
	public @Nullable net.minecraft.entity.Entity moveEntity;

	/** Автономный режим: NPC сам решает, чем заняться, когда задач нет. */
	public volatile boolean autonomous = true;

	public synchronized void clearTargets() {
		moveTarget = null;
		followPlayer = null;
		blockTarget = null;
		blockToPlace = null;
		combatTarget = null;
		resourceQuery = null;
		moveEntity = null;
	}

	public synchronized void setState(NpcState s) {
		this.state = s;
	}

	public String describe() {
		return switch (state) {
			case IDLE -> "idle";
			case GOTO -> "goto " + fmt(moveTarget);
			case FOLLOW -> "follow " + followPlayer;
			case PATROL -> "patrol";
			case COMBAT -> "combat " + (combatTarget != null ? combatTarget.getName().getString() : "?");
			case MINE -> "mine " + blockTarget;
			case BUILD -> "build " + blockToPlace + " at " + blockTarget;
			case SEEK_RESOURCE -> "seek " + resourceQuery;
		};
	}

	private static String fmt(@Nullable Vec3d v) {
		return v == null ? "-" : (int) v.x + "," + (int) v.y + "," + (int) v.z;
	}
}
