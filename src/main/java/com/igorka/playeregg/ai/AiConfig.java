package com.igorka.playeregg.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Клиентский конфиг: config/playeregg.json */
public class AiConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("playeregg.json");

	public String apiKey = "";
	public String personality = "Дружелюбный игрок Minecraft, выполняет просьбы и болтает.";
	public int thinkIntervalTicks = 40;
	public boolean enabled = true;
	public boolean debug = false;
	public String providerName = AiProvider.CEREBRAS.name();

	public AiProvider provider() {
		try { return AiProvider.valueOf(providerName); }
		catch (Exception e) { return AiProvider.CEREBRAS; }
	}

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
		} catch (Exception ignored) {}
		AiConfig cfg = new AiConfig();
		String env = System.getenv("GROQ_API_KEY");
		if (env != null && !env.isBlank()) cfg.apiKey = env;
		return cfg;
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(this));
		} catch (IOException ignored) {}
	}
}
