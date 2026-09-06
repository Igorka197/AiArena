package com.igorka.playeregg.ai;

/** Одно решение ИИ: куда идти и что делать. */
public class AiAction {
	/** move_to | follow | wander | stop | look_at */
	public String action = "stop";
	public Double x, y, z;
	public String target;      // ник игрока для follow/look_at
	public boolean jump;
	public boolean sneak;
	public boolean sprint;
	public String say;         // текст в чат (может быть null)

	public static AiAction idle() {
		return new AiAction();
	}
}
