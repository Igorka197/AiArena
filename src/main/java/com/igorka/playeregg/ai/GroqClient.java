package com.igorka.playeregg.ai;

import com.google.gson.*;
import com.igorka.playeregg.PlayerEggMod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/** Минимальный клиент Groq Chat Completions API. */
public final class GroqClient {
	private static final String ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";
	private static final Gson GSON = new Gson();

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.executor(Executors.newCachedThreadPool(r -> {
				Thread t = new Thread(r, "playeregg-groq");
				t.setDaemon(true);
				return t;
			}))
			.build();

	private GroqClient() {}

	public record Message(String role, String content) {}

	/** Асинхронный запрос. Возвращает содержимое ответа модели (текст) либо null при ошибке. */
	public static CompletableFuture<String> chat(String apiKey, String model, List<Message> messages) {
		JsonArray msgs = new JsonArray();
		for (Message m : messages) {
			JsonObject o = new JsonObject();
			o.addProperty("role", m.role());
			o.addProperty("content", m.content());
			msgs.add(o);
		}

		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		body.add("messages", msgs);
		body.addProperty("temperature", 0.7);
		body.addProperty("max_tokens", 300);
		JsonObject format = new JsonObject();
		format.addProperty("type", "json_object");
		body.add("response_format", format);

		HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
				.timeout(Duration.ofSeconds(20))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
				.build();

		return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
				.thenApply(resp -> {
					if (resp.statusCode() != 200) {
						PlayerEggMod.LOGGER.warn("[PlayerEgg] Groq HTTP {}: {}", resp.statusCode(), resp.body());
						return null;
					}
					try {
						JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
						return root.getAsJsonArray("choices").get(0).getAsJsonObject()
								.getAsJsonObject("message").get("content").getAsString();
					} catch (Exception e) {
						PlayerEggMod.LOGGER.warn("[PlayerEgg] Не удалось разобрать ответ Groq", e);
						return null;
					}
				})
				.exceptionally(t -> {
					PlayerEggMod.LOGGER.warn("[PlayerEgg] Ошибка запроса к Groq: {}", t.toString());
					return null;
				});
	}
}
