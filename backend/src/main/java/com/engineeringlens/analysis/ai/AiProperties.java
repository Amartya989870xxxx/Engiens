package com.engineeringlens.analysis.ai;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI configuration. Model ids live here (and in application.properties), never in code, so models can
 * be added, reordered or retired without a code change.
 *
 * @param providerOrder providers in preference order; models are tried provider by provider, in list order
 */
@ConfigurationProperties("ai")
public record AiProperties(
        @DefaultValue({ "gemini", "groq" }) List<String> providerOrder,
        @DefaultValue Provider gemini,
        @DefaultValue Provider groq,
        @DefaultValue Generation generation,
        @DefaultValue Retry retry,
        @DefaultValue Cooldown cooldown) {

    /**
     * @param baseUrl provider API root, overridable for tests
     * @param models  model ids in preference order
     */
    public record Provider(@DefaultValue("true") boolean enabled, @DefaultValue("") String apiKey, @DefaultValue("") String baseUrl,
            @DefaultValue List<String> models, @DefaultValue("PT5M") Duration readTimeout) {

        public boolean hasKey() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Generation(@DefaultValue("0.2") double temperature, @DefaultValue("32768") int maxOutputTokens,
            @DefaultValue("high") String thinking) {
    }

    /**
     * @param maxRetries retries after the first attempt for retryable errors (2 → up to 3 calls per model)
     * @param maxWait    longest single wait; a longer provider retry-after means "cool down and move on"
     */
    public record Retry(@DefaultValue("2") int maxRetries, @DefaultValue("PT1S") Duration initialDelay,
            @DefaultValue("PT20S") Duration maxWait) {
    }

    /**
     * @param initial          first cooldown after a model keeps failing; doubles per consecutive failure
     * @param max              longest cooldown
     * @param configErrorRecheck how long a misconfigured model (bad key, unknown model) is skipped before
     *                         being tried again; also cleared on every restart
     */
    public record Cooldown(@DefaultValue("PT1M") Duration initial, @DefaultValue("PT30M") Duration max,
            @DefaultValue("PT6H") Duration configErrorRecheck) {
    }

    public Provider provider(String id) {
        return switch (id) {
            case "gemini" -> gemini;
            case "groq" -> groq;
            default -> null;
        };
    }
}
