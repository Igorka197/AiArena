package com.igorka.playeregg.ai;

/** Настройки ИИ, живущие на сервере (присылаются клиентом из меню ввода). */
public final class ServerAiSettings {
	private static String apiKey = "";
	private static String model = "llama-3.3-70b-versatile";
	private static String personality = "Дружелюбный игрок Minecraft.";
	private static int thinkIntervalTicks = 60;
	private static boolean enabled = true;

	private ServerAiSettings() {}

	public static synchronized void update(String key, String mdl, String person, int interval, boolean on) {
		if (key != null && !key.isBlank()) apiKey = key.trim();
		if (mdl != null && !mdl.isBlank()) model = mdl.trim();
		if (person != null && !person.isBlank()) personality = person.trim();
		thinkIntervalTicks = Math.max(20, interval);
		enabled = on;
	}

	public static synchronized String apiKey() {
		if (apiKey.isBlank()) {
			String env = System.getenv("GROQ_API_KEY");
			if (env != null) return env;
		}
		return apiKey;
	}

	public static synchronized boolean hasKey() { return !apiKey().isBlank(); }
	public static synchronized String model() { return model; }
	public static synchronized String personality() { return personality; }
	public static synchronized int thinkIntervalTicks() { return thinkIntervalTicks; }
	public static synchronized boolean enabled() { return enabled; }
}
