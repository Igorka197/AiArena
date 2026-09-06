package com.igorka.playeregg.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Настройки ИИ (Groq). Хранятся в config/playeregg.json на стороне клиента. */
public class AiConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("playeregg.json");

	public String apiKey = "";
	public String model = "llama-3.3-70b-versatile";
	public String personality = "Дружелюбный игрок Minecraft, любит болтать и исследовать мир.";
	public int thinkIntervalTicks = 60;
	public boolean enabled = true;

	private static AiConfig instance;

	public static AiConfig get() {
		if (instance == null) instance = load();
		return instance;
	}

	public boolean hasKey() {
		return apiKey != null && !apiKey.isBlank();
	}

	private static AiConfig load() {
		try {
			if (Files.exists(PATH)) {
				AiConfig cfg = GSON.fromJson(Files.readString(PATH), AiConfig.class);
				if (cfg != null) return cfg;
			}
		} catch (Exception e) {
			// битый конфиг -> дефолт
		}
		AiConfig cfg = new AiConfig();
		// запасной вариант: переменная окружения
		String env = System.getenv("GROQ_API_KEY");
		if (env != null && !env.isBlank()) cfg.apiKey = env;
		return cfg;
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(this));
		} catch (IOException e) {
			// игнор
		}
	}
}
