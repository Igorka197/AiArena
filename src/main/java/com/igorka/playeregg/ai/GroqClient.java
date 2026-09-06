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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Клиент Groq Chat Completions с ретраями. */
public final class GroqClient {
	private static final String ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";
	private static final Gson GSON = new Gson();
	private static final int MAX_RETRIES = 3;

	private static final ScheduledExecutorService SCHEDULER =
			Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "playeregg-retry");
				t.setDaemon(true);
				return t;
			});

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.version(HttpClient.Version.HTTP_1_1)
			.executor(Executors.newCachedThreadPool(r -> {
				Thread t = new Thread(r, "playeregg-groq");
				t.setDaemon(true);
				return t;
			}))
			.build();

	private GroqClient() {}

	public record Message(String role, String content) {}

	/** Последняя ошибка — показываем игроку в чат, чтобы не «молчал непонятно почему». */
	public static volatile String lastError = null;

	public static CompletableFuture<String> chat(String apiKey, List<Message> messages) {
		return attempt(apiKey, messages, 0);
	}

	private static CompletableFuture<String> attempt(String apiKey, List<Message> messages, int tryNo) {
		JsonArray msgs = new JsonArray();
		for (Message m : messages) {
			JsonObject o = new JsonObject();
			o.addProperty("role", m.role());
			o.addProperty("content", m.content());
			msgs.add(o);
		}

		JsonObject body = new JsonObject();
		body.addProperty("model", ServerAiSettings.MODEL);
		body.add("messages", msgs);
		body.addProperty("temperature", 0.4);
		body.addProperty("max_tokens", 400);
		JsonObject fmt = new JsonObject();
		fmt.addProperty("type", "json_object");
		body.add("response_format", fmt);

		HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
				.timeout(Duration.ofSeconds(25))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), java.nio.charset.StandardCharsets.UTF_8))
				.build();

		return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
				.thenCompose(resp -> {
					int code = resp.statusCode();

					if (code == 200) {
						try {
							JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
							JsonObject msg = root.getAsJsonArray("choices").get(0)
									.getAsJsonObject().getAsJsonObject("message");
							JsonElement content = msg.get("content");
							if (content == null || content.isJsonNull() || content.getAsString().isBlank()) {
								lastError = "модель вернула пустой ответ";
								return CompletableFuture.completedFuture(null);
							}
							lastError = null;
							return CompletableFuture.completedFuture(content.getAsString());
						} catch (Exception e) {
							lastError = "не разобрать ответ: " + e;
							return CompletableFuture.completedFuture(null);
						}
					}

					// 429 / 5xx — ретрай с задержкой
					if ((code == 429 || code >= 500) && tryNo < MAX_RETRIES) {
						long delayMs = retryAfterMs(resp, tryNo);
						PlayerEggMod.LOGGER.warn("[PlayerEgg] Groq HTTP {} — повтор через {} мс", code, delayMs);
						CompletableFuture<String> next = new CompletableFuture<>();
						SCHEDULER.schedule(() ->
								attempt(apiKey, messages, tryNo + 1)
										.whenComplete((v, t) -> {
											if (t != null) next.completeExceptionally(t);
											else next.complete(v);
										}), delayMs, TimeUnit.MILLISECONDS);
						return next;
					}

					lastError = switch (code) {
						case 401 -> "неверный API-ключ (401)";
						case 403 -> "доступ запрещён (403)";
						case 429 -> "превышен лимит запросов Groq (429) — увеличь интервал думания";
						default -> "Groq HTTP " + code;
					};
					PlayerEggMod.LOGGER.warn("[PlayerEgg] Groq HTTP {}: {}", code, trim(resp.body()));
					return CompletableFuture.completedFuture(null);
				})
				.exceptionallyCompose(t -> {
					if (tryNo < MAX_RETRIES) {
						CompletableFuture<String> next = new CompletableFuture<>();
						SCHEDULER.schedule(() ->
								attempt(apiKey, messages, tryNo + 1)
										.whenComplete((v, e) -> {
											if (e != null) next.complete(null);
											else next.complete(v);
										}), 1000L * (tryNo + 1), TimeUnit.MILLISECONDS);
						return next;
					}
					lastError = "сеть недоступна: " + t.getClass().getSimpleName();
					PlayerEggMod.LOGGER.warn("[PlayerEgg] Ошибка запроса к Groq: {}", t.toString());
					return CompletableFuture.completedFuture(null);
				});
	}

	private static long retryAfterMs(HttpResponse<String> resp, int tryNo) {
		return resp.headers().firstValue("retry-after")
				.map(v -> {
					try { return (long) (Double.parseDouble(v) * 1000); }
					catch (NumberFormatException e) { return -1L; }
				})
				.filter(v -> v > 0)
				.orElse(800L * (1L << tryNo));
	}

	private static String trim(String s) {
		return s == null ? "" : s.length() > 300 ? s.substring(0, 300) : s;
	}
}
