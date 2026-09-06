package com.igorka.playeregg.ai;

import com.google.gson.*;
import com.igorka.playeregg.PlayerEggMod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

/**
 * Асинхронный клиент Groq Chat Completions.
 *
 * ИСПРАВЛЕНИЯ HTTP 400:
 *  1. `max_tokens` НЕ поддерживается reasoning-моделями (openai/gpt-oss-*) — используем
 *     `max_completion_tokens`. Именно это давало Bad Request.
 *  2. При `response_format: json_object` в messages ОБЯЗАН встречаться литерал "json",
 *     иначе API отвечает 400 ("'messages' must contain the word 'json'").
 *     Гарантируем это методом ensureJsonLiteral().
 *  3. `reasoning_effort: low` — режем размышления, чтобы reasoning-токены не съедали
 *     весь бюджет и content не приходил пустым (это же снижает latency).
 *  4. Тело сериализуется Gson'ом из типизированных объектов — никакой ручной склейки строк.
 *
 * Все вызовы неблокирующие: sendAsync + свой пул демон-потоков. Игровой поток НИКОГДА
 * не ждёт сеть.
 */
public final class GroqClient {
	private static final Gson GSON = new Gson();
	private static final int MAX_RETRIES = 3;

	/** Бюджет с запасом: reasoning + сам ответ. Мало -> пустой content и finish_reason=length. */
	private static final int MAX_COMPLETION_TOKENS = 220;

	private static final ScheduledExecutorService SCHEDULER =
			Executors.newSingleThreadScheduledExecutor(daemon("playeregg-retry"));

	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.version(HttpClient.Version.HTTP_2)
			.executor(Executors.newFixedThreadPool(2, daemon("playeregg-groq")))
			.build();

	private GroqClient() {}

	private static ThreadFactory daemon(String name) {
		return r -> {
			Thread t = new Thread(r, name);
			t.setDaemon(true);
			t.setPriority(Thread.NORM_PRIORITY - 1);  // не мешаем тику сервера
			return t;
		};
	}

	public record Message(String role, String content) {}

	/** Последняя ошибка для показа игроку. */
	public static volatile String lastError = null;
	/** Замер задержки последнего успешного запроса (мс) — видно в отладке. */
	public static volatile long lastLatencyMs = 0;

	public static CompletableFuture<String> chat(String apiKey, List<Message> messages) {
		return attempt(apiKey, ensureJsonLiteral(messages), 0, System.nanoTime());
	}

	/**
	 * json_object-режим требует слова "json" в сообщениях. Если промпт его потерял —
	 * дописываем в системное сообщение, иначе гарантированный HTTP 400.
	 */
	private static List<Message> ensureJsonLiteral(List<Message> messages) {
		boolean has = messages.stream()
				.anyMatch(m -> m.content() != null && m.content().toLowerCase().contains("json"));
		if (has) return messages;

		List<Message> copy = new java.util.ArrayList<>(messages);
		copy.add(0, new Message("system", "Reply strictly with a single valid JSON object."));
		return copy;
	}

	private static CompletableFuture<String> attempt(String apiKey, List<Message> messages,
	                                                 int tryNo, long startNanos) {
		final String payload = buildPayload(messages);

		HttpRequest req = HttpRequest.newBuilder(URI.create(ServerAiSettings.endpoint()))
				.timeout(Duration.ofSeconds(12))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + apiKey.trim())
				.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
				.build();

		return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.thenCompose(resp -> handle(resp, apiKey, messages, tryNo, startNanos))
				.exceptionallyCompose(t -> retryOrFail(apiKey, messages, tryNo, startNanos, t));
	}

	/** Формируем тело строго по схеме Groq/OpenAI. */
	private static String buildPayload(List<Message> messages) {
		JsonArray msgs = new JsonArray();
		for (Message m : messages) {
			// Пустой content -> 400. Пропускаем такие сообщения.
			if (m.content() == null || m.content().isBlank()) continue;
			JsonObject o = new JsonObject();
			o.addProperty("role", m.role());
			o.addProperty("content", m.content());
			msgs.add(o);
		}

		JsonObject body = new JsonObject();
		body.addProperty("model", ServerAiSettings.model());
		body.add("messages", msgs);
		body.addProperty("temperature", 0.2);
		// ВАЖНО: max_completion_tokens, а НЕ max_tokens (иначе 400 на reasoning-моделях)
		body.addProperty("max_completion_tokens", MAX_COMPLETION_TOKENS);
		body.addProperty("top_p", 1);
		body.addProperty("stream", false);
		// reasoning_effort поддерживают только gpt-oss; для других моделей это 400
		if (ServerAiSettings.provider().isReasoningModel()) {
			body.addProperty("reasoning_effort", "low");
		}

		JsonObject fmt = new JsonObject();
		fmt.addProperty("type", "json_object");
		body.add("response_format", fmt);

		return GSON.toJson(body);
	}

	private static CompletableFuture<String> handle(HttpResponse<String> resp, String apiKey,
	                                                List<Message> messages, int tryNo, long startNanos) {
		int code = resp.statusCode();

		if (code == 200) {
			try {
				JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
				JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
				JsonElement content = choice.getAsJsonObject("message").get("content");

				// Диагностика reasoning-токенов: если упёрлись в бюджет — content будет пустым
				JsonObject usage = root.getAsJsonObject("usage");
				if (usage != null && ServerAiSettings.debug()) {
					PlayerEggMod.LOGGER.info("[PlayerEgg] usage={}", usage);
				}

				if (content == null || content.isJsonNull() || content.getAsString().isBlank()) {
					String finish = choice.has("finish_reason") ? choice.get("finish_reason").getAsString() : "?";
					lastError = "пустой ответ модели (finish_reason=" + finish + ")";
					return CompletableFuture.completedFuture(null);
				}

				lastLatencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
				lastError = null;
				return CompletableFuture.completedFuture(content.getAsString());
			} catch (Exception e) {
				lastError = "не разобрать ответ: " + e;
				return CompletableFuture.completedFuture(null);
			}
		}

		// 400 — ошибка в запросе: логируем тело целиком, ретраить бессмысленно
		if (code == 400) {
			lastError = "400 Bad Request: " + extractApiMessage(resp.body());
			PlayerEggMod.LOGGER.error("[PlayerEgg] Groq 400. Ответ: {}", trim(resp.body()));
			return CompletableFuture.completedFuture(null);
		}

		if ((code == 429 || code >= 500) && tryNo < MAX_RETRIES) {
			long delay = retryAfterMs(resp, tryNo);
			PlayerEggMod.LOGGER.warn("[PlayerEgg] Groq HTTP {} — повтор через {} мс", code, delay);
			return delayed(() -> attempt(apiKey, messages, tryNo + 1, startNanos), delay);
		}

		lastError = switch (code) {
			case 401 -> "неверный API-ключ (401)";
			case 403 -> "доступ запрещён (403)";
			case 404 -> "модель не найдена (404): " + ServerAiSettings.model();
			case 429 -> "лимит запросов Groq (429) — увеличь интервал думания";
			default -> "Groq HTTP " + code;
		};
		PlayerEggMod.LOGGER.warn("[PlayerEgg] Groq HTTP {}: {}", code, trim(resp.body()));
		return CompletableFuture.completedFuture(null);
	}

	private static CompletableFuture<String> retryOrFail(String apiKey, List<Message> messages,
	                                                     int tryNo, long startNanos, Throwable t) {
		if (tryNo < MAX_RETRIES) {
			return delayed(() -> attempt(apiKey, messages, tryNo + 1, startNanos), 700L * (tryNo + 1));
		}
		lastError = "сеть недоступна: " + t.getClass().getSimpleName();
		PlayerEggMod.LOGGER.warn("[PlayerEgg] Ошибка запроса: {}", t.toString());
		return CompletableFuture.completedFuture(null);
	}

	private static CompletableFuture<String> delayed(Supplier task, long delayMs) {
		CompletableFuture<String> out = new CompletableFuture<>();
		SCHEDULER.schedule(() -> task.get().whenComplete((v, e) -> out.complete(e != null ? null : v)),
				delayMs, TimeUnit.MILLISECONDS);
		return out;
	}

	@FunctionalInterface
	private interface Supplier {
		CompletableFuture<String> get();
	}

	private static String extractApiMessage(String body) {
		try {
			return JsonParser.parseString(body).getAsJsonObject()
					.getAsJsonObject("error").get("message").getAsString();
		} catch (Exception e) {
			return trim(body);
		}
	}

	private static long retryAfterMs(HttpResponse<String> resp, int tryNo) {
		return resp.headers().firstValue("retry-after")
				.map(v -> { try { return (long) (Double.parseDouble(v) * 1000); }
					catch (NumberFormatException e) { return -1L; } })
				.filter(v -> v > 0)
				.orElse(600L * (1L << tryNo));
	}

	private static String trim(String s) {
		return s == null ? "" : s.length() > 400 ? s.substring(0, 400) + "…" : s;
	}
}
