package com.igorka.playeregg.ai;

/**
 * Провайдеры LLM. Все OpenAI-совместимые, поэтому меняется только URL и модель.
 * Скорости замерены на апрель 2026.
 */
public enum AiProvider {
	/** ~1800 т/с, 1 млн токенов/день бесплатно, TTFT <100 мс. Самый быстрый. */
	CEREBRAS("Cerebras (самый быстрый)",
			"https://api.cerebras.ai/v1/chat/completions",
			"llama-3.3-70b",
			"https://cloud.cerebras.ai"),

	/** ~1000 т/с на 20b. Лимит 1000 запросов/день. */
	GROQ("Groq",
			"https://api.groq.com/openai/v1/chat/completions",
			"openai/gpt-oss-20b",
			"https://console.groq.com/keys");

	public final String displayName;
	public final String endpoint;
	public final String model;
	public final String keyUrl;

	AiProvider(String displayName, String endpoint, String model, String keyUrl) {
		this.displayName = displayName;
		this.endpoint = endpoint;
		this.model = model;
		this.keyUrl = keyUrl;
	}

	/** Reasoning-модели требуют особых параметров запроса. */
	public boolean isReasoningModel() {
		return model.contains("gpt-oss");
	}

	@Override
	public String toString() { return displayName; }
}
