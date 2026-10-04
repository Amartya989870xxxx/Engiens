package com.engineeringlens.analysis.ai;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Groq's OpenAI-compatible chat completions API, used as fallback. Reasoning controls differ per model
 * family; translating the generic "thinking" setting into the right parameter is this adapter's job.
 */
@Component
public class GroqProvider implements AiProvider {

    public static final String ID = "groq";

    private final AiProperties.Provider config;
    private final RestClient http;
    private final ObjectMapper json;

    @Autowired
    public GroqProvider(AiProperties properties, ObjectMapper json) {
        this(properties.groq(), ProviderHttp.builder(properties.groq().baseUrl(), properties.groq().readTimeout()), json);
    }

    GroqProvider(AiProperties.Provider config, RestClient.Builder builder, ObjectMapper json) {
        this.config = config;
        this.http = builder.build();
        this.json = json;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean configured() {
        return config.enabled() && config.hasKey();
    }

    @Override
    public AiReply generate(String model, AiPrompt prompt, AiGenerationSettings settings) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "system", "content", prompt.system()),
                Map.of("role", "user", "content", prompt.user())));
        body.put("temperature", settings.temperature());
        body.put("max_completion_tokens", settings.maxOutputTokens());
        if (settings.jsonOutput()) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        String lower = model.toLowerCase(Locale.ROOT);
        if (lower.startsWith("openai/gpt-oss") && settings.thinking() != null) {
            body.put("reasoning_effort", settings.thinking()); // gpt-oss: low | medium | high
        } else if (lower.contains("qwen")) {
            body.put("reasoning_format", "hidden"); // JSON mode doesn't allow raw <think> output
        }
        try {
            return http.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((req, res) -> {
                        String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        if (res.getStatusCode().isError()) {
                            throw classify(res.getStatusCode(), text, res.getHeaders().getFirst("retry-after"));
                        }
                        return parse(text);
                    });
        } catch (ResourceAccessException e) {
            throw ProviderHttp.networkFailure(e);
        }
    }

    private AiReply parse(String body) {
        JsonNode root = json.readTree(body);
        JsonNode choice = root.path("choices").path(0);
        if (choice.path("finish_reason").asString("").equals("length")) {
            throw new AiProviderException(AiFailureType.EMPTY_RESPONSE, "Groq output was cut off at the token limit");
        }
        String text = choice.path("message").path("content").asString("");
        if (text.isBlank()) {
            throw new AiProviderException(AiFailureType.EMPTY_RESPONSE, "Groq returned no content");
        }
        JsonNode usage = root.path("usage");
        return new AiReply(text, usage.has("prompt_tokens") ? usage.path("prompt_tokens").asInt() : null,
                usage.has("completion_tokens") ? usage.path("completion_tokens").asInt() : null);
    }

    /** Groq error body: {"error":{"message","type","code"}}; rate limits carry a retry-after header (seconds). */
    AiProviderException classify(HttpStatusCode status, String body, String retryAfterHeader) {
        String message = "";
        String code = "";
        try {
            JsonNode error = json.readTree(body).path("error");
            message = error.path("message").asString("");
            code = error.path("code").asString("");
        } catch (RuntimeException ignored) {
            // classify by status alone
        }
        String safe = ProviderHttp.sanitize("HTTP " + status.value() + (code.isEmpty() ? "" : " " + code)
                + (message.isEmpty() ? "" : ": " + message), config.apiKey());
        String lower = message.toLowerCase(Locale.ROOT);
        Duration retryAfter = ProviderHttp.parseDelay(retryAfterHeader);
        int s = status.value();
        AiFailureType type;
        if (s == 401 || s == 403 || code.equals("invalid_api_key")) {
            type = AiFailureType.AUTH_INVALID;
        } else if (s == 404 || code.equals("model_not_found") || code.equals("model_decommissioned")) {
            type = AiFailureType.MODEL_NOT_FOUND;
        } else if (s == 413 || lower.contains("request too large") || lower.contains("reduce your message size")) {
            type = AiFailureType.CONTEXT_TOO_LARGE; // waiting won't make a too-large request fit
        } else if (s == 429) {
            type = lower.contains("quota") || lower.contains("per day") ? AiFailureType.QUOTA_EXCEEDED : AiFailureType.RATE_LIMITED;
        } else if (s == 400 && code.equals("json_validate_failed")) {
            type = AiFailureType.EMPTY_RESPONSE; // the model failed to produce valid JSON
        } else if (s == 408 || s == 504) {
            type = AiFailureType.TIMEOUT;
        } else if (s == 503 || s == 498) {
            type = AiFailureType.UNAVAILABLE; // 498: flex-tier capacity exceeded
        } else if (s >= 500) {
            type = AiFailureType.SERVER_ERROR;
        } else {
            type = AiFailureType.REQUEST_INVALID;
        }
        return new AiProviderException(type, safe, retryAfter);
    }
}
