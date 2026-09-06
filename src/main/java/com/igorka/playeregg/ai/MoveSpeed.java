package com.igorka.playeregg.ai;

/**
 * Режимы перемещения. Множители подобраны так, чтобы при
 * GENERIC_MOVEMENT_SPEED = 0.30 итоговая скорость совпадала с игроком:
 *   SNEAK ≈ 1.3 бл/с, WALK ≈ 4.3 бл/с, RUN ≈ 5.6 бл/с.
 */
public enum MoveSpeed {
	SNEAK(0.45D),
	WALK(1.0D),
	RUN(1.35D);

	public final double multiplier;

	MoveSpeed(double m) { this.multiplier = m; }

	public static MoveSpeed parse(String s, MoveSpeed def) {
		if (s == null) return def;
		return switch (s.toLowerCase().trim()) {
			case "sneak", "crouch", "slow" -> SNEAK;
			case "run", "sprint", "fast" -> RUN;
			case "walk", "normal" -> WALK;
			default -> def;
		};
	}
}
