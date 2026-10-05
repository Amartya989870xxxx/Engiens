package com.engineeringlens.analysis.ai;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Chooses which model handles a request and makes the AI layer resilient. For every request it walks
 * the configured models in priority order (Gemini first), so the preferred model is tried again as
 * soon as its cooldown expires. Per model: retry transient errors with exponential backoff and jitter,
 * then cool the model down and fall back; skip misconfigured models; give invalid output one repair
 * attempt, then move on without blaming the model's health.
 */
@Service
public class AiModelRouter {

    private static final Logger log = LoggerFactory.getLogger(AiModelRouter.class);

    private final Map<String, AiProvider> providers;
    private final AiProperties properties;
    private final AiModelHealthService health;
    private final Sleeper sleeper;
    private final Clock clock;

    public AiModelRouter(List<AiProvider> providers, AiProperties properties, AiModelHealthService health, Sleeper sleeper,
            Clock clock) {
        this.providers = providers.stream().collect(Collectors.toMap(AiProvider::id, p -> p));
        this.properties = properties;
        this.health = health;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    /** A model in priority order. */
    public record Candidate(String provider, String model) {
    }

    /**
     * The validated result plus which model produced it.
     *
     * @param fallbackUsed   true when the highest-priority configured model didn't produce the result
     * @param fallbackReason why the first choice wasn't used, e.g. GEMINI_QUOTA_EXCEEDED (null if not)
     * @param attempts       provider calls made, including retries and repair attempts
     */
    public record Routed<T>(T value, String provider, String model, boolean fallbackUsed, String fallbackReason, int attempts,
            Integer inputTokens, Integer outputTokens, long durationMs) {
    }

    /** All configured models, in priority order. */
    public List<Candidate> candidates() {
        List<Candidate> list = new ArrayList<>();
        for (String id : properties.providerOrder()) {
            AiProperties.Provider config = properties.provider(id);
            if (config != null && providers.containsKey(id)) {
                config.models().forEach(m -> list.add(new Candidate(id, m)));
            }
        }
        return list;
    }

    /** Whether the highest-priority configured model can take a request right now (not cooling down or misconfigured). */
    public boolean preferredModelAvailable() {
        return candidates().stream().filter(c -> providers.get(c.provider()).configured()).findFirst()
                .map(c -> health.eligible(c.provider(), c.model())).orElse(false);
    }

    /**
     * @param parse  validates the raw text into a result, throwing {@link InvalidAiOutputException} if invalid
     * @param repair builds a stricter prompt from the validation error, for one repair attempt per model
     */
    public <T> Routed<T> generate(AiPrompt prompt, AiGenerationSettings settings, Function<String, T> parse,
            Function<String, AiPrompt> repair) {
        long start = clock.millis();
        List<Candidate> configured = candidates().stream().filter(c -> providers.get(c.provider()).configured()).toList();
        if (configured.isEmpty()) {
            throw new AiUnavailableException(AiUnavailableException.NOT_CONFIGURED,
                    "AI review isn't configured on this server yet.");
        }
        int[] attempts = { 0 };
        String fallbackReason = null;
        String lastProblem = null;
        for (int i = 0; i < configured.size(); i++) {
            Candidate c = configured.get(i);
            AiProvider provider = providers.get(c.provider());
            if (!health.eligible(c.provider(), c.model())) {
                AiModelHealth h = health.get(c.provider(), c.model());
                String reason = reason(c, h.getState().name());
                fallbackReason = fallbackReason == null ? reason : fallbackReason;
                lastProblem = reason;
                log.info("AI routing: skipping {}:{} ({})", c.provider(), c.model(), h.getState());
                continue;
            }
            log.info("AI routing: trying {}:{}{}", c.provider(), c.model(), i == 0 ? "" : " (fallback)");
            try {
                // First answer: invalid JSON and empty/cut-off output both earn exactly one repair attempt.
                String problem = null;
                AiReply reply = null;
                T value = null;
                try {
                    reply = callWithRetries(provider, c, prompt, settings, attempts);
                } catch (AiProviderException e) {
                    if (e.type() != AiFailureType.EMPTY_RESPONSE) {
                        throw e;
                    }
                    problem = e.getMessage();
                }
                health.recordSuccess(c.provider(), c.model()); // it answered: reachable and healthy
                if (reply != null) {
                    try {
                        value = parse.apply(reply.text());
                    } catch (InvalidAiOutputException invalid) {
                        problem = invalid.getMessage();
                    }
                }
                if (problem != null) {
                    log.warn("AI output unusable from {}:{}: {}; attempting one repair", c.provider(), c.model(), problem);
                    reply = callWithRetries(provider, c, repair.apply(problem), settings, attempts);
                    value = parse.apply(reply.text());
                }
                return new Routed<>(value, c.provider(), c.model(), i > 0, i > 0 ? fallbackReason : null, attempts[0],
                        reply.inputTokens(), reply.outputTokens(), clock.millis() - start);
            } catch (InvalidAiOutputException invalid) {
                // The model is reachable; its answer just wasn't usable. Try the next model, no health penalty.
                log.warn("AI output still invalid from {}:{} after repair: {}", c.provider(), c.model(), invalid.getMessage());
                String reason = reason(c, "INVALID_OUTPUT");
                fallbackReason = fallbackReason == null ? reason : fallbackReason;
                lastProblem = reason;
            } catch (AiProviderException e) {
                String reason = reason(c, e.type().name());
                fallbackReason = fallbackReason == null ? reason : fallbackReason;
                lastProblem = reason;
                handleFailure(c, e);
            }
        }
        throw new AiUnavailableException(AiUnavailableException.UNAVAILABLE,
                "No AI model could complete the review right now (last problem: " + lastProblem + ").");
    }

    /** Applies the failure classification to model health, or stops routing for our own bad request. */
    private void handleFailure(Candidate c, AiProviderException e) {
        log.warn("AI provider failure: {}:{} type={} message={}", c.provider(), c.model(), e.type(), e.getMessage());
        switch (e.type()) {
            case RATE_LIMITED, QUOTA_EXCEEDED, TIMEOUT, SERVER_ERROR, UNAVAILABLE, NETWORK ->
                    health.recordCooldown(c.provider(), c.model(), e);
            case MODEL_NOT_FOUND -> health.recordConfigurationError(c.provider(), c.model(), e);
            case AUTH_INVALID -> {
                // A bad key affects every model of that provider: mark them all, then the next provider is tried.
                for (String model : properties.provider(c.provider()).models()) {
                    health.recordConfigurationError(c.provider(), model, e);
                }
            }
            case CONTEXT_TOO_LARGE, EMPTY_RESPONSE -> {
                // Unsuitable for this request, not unhealthy: no health change, try the next model.
            }
            case REQUEST_INVALID -> throw new AiUnavailableException(AiUnavailableException.REQUEST_REJECTED,
                    "The AI provider rejected the review request.");
        }
    }

    private AiReply callWithRetries(AiProvider provider, Candidate c, AiPrompt prompt, AiGenerationSettings settings, int[] attempts) {
        AiProperties.Retry retry = properties.retry();
        for (int attempt = 0;; attempt++) {
            attempts[0]++;
            try {
                return provider.generate(c.model(), prompt, settings);
            } catch (AiProviderException e) {
                if (!e.type().retryable() || attempt >= retry.maxRetries()) {
                    throw e;
                }
                Duration wait = backoff(retry.initialDelay(), attempt);
                if (e.retryAfter() != null) {
                    if (e.retryAfter().compareTo(retry.maxWait()) > 0) {
                        throw e; // e.g. "quota resets in an hour": waiting here is pointless; cool down instead
                    }
                    wait = e.retryAfter().compareTo(wait) > 0 ? e.retryAfter() : wait;
                }
                log.info("AI retry: {}:{} after {} ({}ms)", c.provider(), c.model(), e.type(), wait.toMillis());
                sleeper.sleep(wait);
            }
        }
    }

    /** initial × 2^attempt, ±25% jitter so many clients don't retry in lockstep. */
    static Duration backoff(Duration initial, int attempt) {
        long base = initial.toMillis() << attempt;
        double jitter = 0.75 + ThreadLocalRandom.current().nextDouble() * 0.5;
        return Duration.ofMillis((long) (base * jitter));
    }

    private static String reason(Candidate c, String what) {
        return c.provider().toUpperCase(Locale.ROOT) + "_" + what;
    }
}
