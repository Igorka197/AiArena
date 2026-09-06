package com.igorka.playeregg.ai;

/** Решение LLM: какое состояние включить и с какой целью. */
public class AiAction {
	public String action = "stop";
	public Double x, y, z;
	public String target;    // ник игрока / тип моба
	public String block;     // id блока для build
	public String resource;  // что искать для seek
	public String item;      // id предмета для equip
	public Boolean jump, sneak, sprint;
	public String speed;   // walk | run | sneak
	public String say;

	public static boolean on(Boolean b) { return b != null && b; }
}
