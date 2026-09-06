package com.igorka.playeregg.ai;

import java.util.List;

/** Одно решение ИИ: что делать NPC. */
public class AiAction {
	/**
	 * stop | move_to | follow | wander | look_at |
	 * break_block | place_block | attack | use_item | equip | drop | swing | jump_to
	 */
	public String action = "stop";

	public Double x, y, z;      // координаты для move_to / break_block / place_block / jump_to
	public String target;       // ник игрока или тип моба
	public String block;        // id блока для place_block, напр. "minecraft:stone"
	public String item;         // id предмета для equip / use_item
	public String face = "up";  // сторона для place_block

	public Boolean jump;
	public Boolean sneak;
	public Boolean sprint;

	public String say;          // реплика в чат
	public List<AiAction> then; // необязательная цепочка действий

	public boolean flag(Boolean b) {
		return b != null && b;
	}
}
