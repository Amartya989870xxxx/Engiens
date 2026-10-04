package com.engineeringlens.analysis.ai;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Google Gemini via the generateContent REST API. Translates the neutral prompt into Gemini's request
 * (system instruction, JSON response type, thinking level) and Gemini's errors into failure types.
 */
@Component
public class GeminiProvider implements AiProvider {

    public static final String ID = "gemini";

    private final AiProperties.Provider config;
    private final RestClient http;
    private final ObjectMapper json;

    @Autowired
    public GeminiProvider(AiProperties properties, ObjectMapper json) {
        this(properties.gemini(), ProviderHttp.builder(properties.gemini().baseUrl(), properties.gemini().readTimeout()), json);
    }

    GeminiProvider(AiProperties.Provider config, RestClient.Builder builder, ObjectMapper json) {
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
        Map<String, Object> generation = new LinkedHashMap<>();
        // Google recommends leaving Gemini 3 at its default temperature (1.0): lower values can cause
        // looping. Low randomness comes from strict JSON output and the instructions instead.
        generation.put("maxOutputTokens", settings.maxOutputTokens());
        if (settings.jsonOutput()) {
            generation.put("responseMimeType", "application/json");
        }
        if (settings.thinking() != null) {
            generation.put("thinkingConfig", Map.of("thinkingLevel", settings.thinking()));
        }
        Map<String, Object> body = Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", prompt.system()))),
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt.user())))),
                "generationConfig", generation);
        try {
            return http.post()
                    .uri("/models/{model}:generateContent", model)
                    .header("x-goog-api-key", config.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((req, res) -> {
                        String text = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        if (res.getStatusCode().isError()) {
                            throw classify(res.getStatusCode(), text, res.getHeaders().getFirst("Retry-After"));
                        }
                        return parse(text);
                    });
        } catch (ResourceAccessException e) {
            throw ProviderHttp.networkFailure(e);
        }
    }

    private AiReply parse(String body) {
        JsonNode root = json.readTree(body);
        JsonNode candidate = root.path("candidates").path(0);
        if (candidate.isMissingNode()) {
            String block = root.path("promptFeedback").path("blockReason").asString("");
            throw new AiProviderException(AiFailureType.EMPTY_RESPONSE,
                    block.isEmpty() ? "Gemini returned no candidates" : "Gemini blocked the prompt: " + block);
        }
        String finish = candidate.path("finishReason").asString("");
        if (finish.equals("MAX_TOKENS")) {
            throw new AiProviderException(AiFailureType.EMPTY_RESPONSE, "Gemini output was cut off at the token limit");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (!part.path("thought").asBoolean(false)) { // thought summaries are not the answer
                text.append(part.path("text").asString(""));
            }
        }
        if (text.isEmpty()) {
            throw new AiProviderException(AiFailureType.EMPTY_RESPONSE, "Gemini returned no text (finishReason " + finish + ")");
        }
        JsonNode usage = root.path("usageMetadata");
        Integer in = usage.has("promptTokenCount") ? usage.path("promptTokenCount").asInt() : null;
        Integer out = usage.has("candidatesTokenCount")
                ? usage.path("candidatesTokenCount").asInt() + usage.path("thoughtsTokenCount").asInt(0) : null;
        return new AiReply(text.toString(), in, out);
    }

    /** Gemini error body: {"error":{"code","message","status","details":[{reason}|{retryDelay}]}}. */
    AiProviderException classify(HttpStatusCode status, String body, String retryAfterHeader) {
        String message = "";
        String grpcStatus = "";
        Duration retryAfter = ProviderHttp.parseDelay(retryAfterHeader);
        try {
            JsonNode error = json.readTree(body).path("error");
            message = error.path("message").asString("");
            grpcStatus = error.path("status").asString("");
            for (JsonNode detail : error.path("details")) {
                if (detail.has("retryDelay")) {
                    retryAfter = ProviderHttp.parseDelay(detail.path("retryDelay").asString());
                }
                if (detail.path("reason").asString("").equals("API_KEY_INVALID")) {
                    grpcStatus = "API_KEY_INVALID";
                }
            }
        } catch (RuntimeException ignored) {
            // Not JSON (e.g. a proxy error page): classify by status alone.
        }
        String safe = ProviderHttp.sanitize("HTTP " + status.value() + (grpcStatus.isEmpty() ? "" : " " + grpcStatus)
                + (message.isEmpty() ? "" : ": " + message), config.apiKey());
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        int code = status.value();
        AiFailureType type;
        if (grpcStatus.equals("API_KEY_INVALID") || lower.contains("api key not valid") || code == 401 || code == 403) {
            type = AiFailureType.AUTH_INVALID; // Gemini reports a bad key as 400 API_KEY_INVALID
        } else if (code == 404 || lower.contains("is not found") || lower.contains("not supported for generatecontent")) {
            type = AiFailureType.MODEL_NOT_FOUND;
        } else if (code == 429) {
            type = lower.contains("quota") ? AiFailureType.QUOTA_EXCEEDED : AiFailureType.RATE_LIMITED;
        } else if (code == 400 && (lower.contains("token count") || lower.contains("exceeds the maximum number of tokens"))) {
            type = AiFailureType.CONTEXT_TOO_LARGE;
        } else if (code == 400 && grpcStatus.equals("FAILED_PRECONDITION")) {
            type = AiFailureType.AUTH_INVALID; // e.g. API not available for this account/region: a configuration problem
        } else if (code == 408 || code == 504) {
            type = AiFailureType.TIMEOUT;
        } else if (code == 503) {
            type = AiFailureType.UNAVAILABLE;
        } else if (code >= 500) {
            type = AiFailureType.SERVER_ERROR;
        } else {
            type = AiFailureType.REQUEST_INVALID;
        }
        return new AiProviderException(type, safe, retryAfter);
    }
}
