package com.igorka.playeregg.ai;

/** Настройки ИИ на сервере (присылаются клиентом из меню). */
public final class ServerAiSettings {
	/** Активный провайдер (по умолчанию — самый быстрый). */
	private static AiProvider provider = AiProvider.CEREBRAS;

	public static synchronized AiProvider provider() { return provider; }
	public static synchronized String model() { return provider.model; }
	public static synchronized String endpoint() { return provider.endpoint; }

	/** Совместимость со старым кодом. */
	public static synchronized String MODEL() { return provider.model; }

	private static String apiKey = "";
	private static String personality = "Дружелюбный игрок Minecraft, выполняет просьбы и болтает.";
	private static int thinkIntervalTicks = 40;
	private static boolean enabled = true;
	private static boolean debug = false;

	private ServerAiSettings() {}

	public static synchronized void update(String key, String person, int interval,
	                                       boolean on, boolean dbg, AiProvider prov) {
		if (prov != null) provider = prov;
		if (key != null) apiKey = key.trim();
		if (person != null && !person.isBlank()) personality = person.trim();
		thinkIntervalTicks = Math.max(20, Math.min(400, interval));
		enabled = on;
		debug = dbg;
	}

	public static synchronized String apiKey() {
		if (apiKey.isBlank()) {
			String env = System.getenv("GROQ_API_KEY");
			if (env != null) return env.trim();
		}
		return apiKey;
	}

	public static synchronized boolean hasKey() { return !apiKey().isBlank(); }
	public static synchronized String personality() { return personality; }
	public static synchronized int thinkIntervalTicks() { return thinkIntervalTicks; }
	public static synchronized boolean enabled() { return enabled; }
	public static synchronized boolean debug() { return debug; }
}
