# Настройка быстрого бесплатного API

## Сравнение бесплатных провайдеров (по latency)

| Провайдер | Модель | Скорость | Free-tier лимит | TTFT* |
|---|---|---|---|---|
| **Groq** ⭐ | `openai/gpt-oss-20b` | **~1000 т/с** | 30 RPM / 1000 RPD | ~0.2 с |
| **Groq** | `openai/gpt-oss-120b` | ~500 т/с | 30 RPM / 1000 RPD | ~0.3 с |
| **Groq** | `llama-3.1-8b-instant` | ~560 т/с | 30 RPM / **14 400 RPD** | ~0.2 с |
| Cerebras | `llama3.1-8b` | ~1800 т/с | 30 RPM / 14 400 RPD | ~0.15 с |
| Together AI | `Llama-3.3-70B-Turbo-Free` | ~90 т/с | 60 RPM | ~0.6 с |
| OpenRouter | `*:free` модели | 20–100 т/с | 20 RPM / 50 RPD | 1–3 с |

\* TTFT — time to first token, главный вклад в ощущаемую задержку.

**Рекомендация для этого мода:** `openai/gpt-oss-20b` на Groq — оптимум скорости и ума.
Если NPC много и упираешься в лимит запросов — `llama-3.1-8b-instant` (14 400 запросов/день).

> ⚠️ `llama-3.3-70b-versatile` и `llama-3.1-8b-instant` выключаются Groq **16.08.2026**.
> Актуальная замена — `openai/gpt-oss-120b` / `openai/gpt-oss-20b`.

## Как сменить модель в моде

`src/main/java/com/igorka/playeregg/ai/ServerAiSettings.java`:

```java
// Быстрее всего:
public static final String MODEL = "openai/gpt-oss-20b";
// Умнее (по умолчанию):
public static final String MODEL = "openai/gpt-oss-120b";
```

## Пример корректного запроса (то, что чинит HTTP 400)

```bash
curl https://api.groq.com/openai/v1/chat/completions \
  -H "Authorization: Bearer $GROQ_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "openai/gpt-oss-120b",
    "messages": [
      {"role": "system", "content": "Reply with a single valid json object."},
      {"role": "user",   "content": "{\"hp\":20}"}
    ],
    "max_completion_tokens": 1024,
    "reasoning_effort": "low",
    "response_format": {"type": "json_object"}
  }'
```

### Три причины HTTP 400 и пустых ответов

1. **`max_tokens` не поддерживается reasoning-моделями** (`openai/gpt-oss-*`).
   Нужен **`max_completion_tokens`**. Это была главная причина 400.
2. **`response_format: json_object` требует слова «json» в сообщениях.**
   Иначе: `'messages' must contain the word 'json'... ` → 400.
   В коде это гарантирует `GroqClient.ensureJsonLiteral()`.
3. **Пустой `content` при HTTP 200.** Reasoning-токены съедают бюджет,
   приходит `finish_reason: "length"` и пустая строка. Лечится
   `reasoning_effort: "low"` + бюджетом 1024 токена.

## Подключение альтернативы (Cerebras) — API OpenAI-совместимый

Достаточно поменять две константы, остальной код не трогается:

```java
// GroqClient.java
private static final String ENDPOINT = "https://api.cerebras.ai/v1/chat/completions";
// ServerAiSettings.java
public static final String MODEL = "llama3.1-8b";
```

Ключ бесплатно: https://cloud.cerebras.ai

## Тюнинг задержки в игре

| Настройка | Где | Эффект |
|---|---|---|
| Интервал думания | меню (G) | 60 тиков = 3 с. Ниже 40 — риск словить 429 |
| `reasoning_effort` | `GroqClient` | `low` вдвое режет latency |
| `MAX_HISTORY` | `AiBrain` | меньше история → меньше токенов → быстрее |
| Радиус сканов | `AiBrain` | меньше данных в промпте → быстрее ответ |

Важно: **задержка API больше не влияет на плавность бота** — пока ответ летит,
NPC продолжает идти, драться и копать по локальной FSM.
