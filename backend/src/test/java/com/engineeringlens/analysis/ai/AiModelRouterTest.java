package com.engineeringlens.analysis.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Routing, retries, cooldown, fallback and Gemini recovery, with scripted providers and a fake clock. */
class AiModelRouterTest {

    static final String OK = "VALID";

    /** A provider whose answers are scripted per model; records every call. */
    static final class ScriptedProvider implements AiProvider {
        final String id;
        boolean configured = true;
        final Map<String, Deque<Object>> script = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        ScriptedProvider(String id) {
            this.id = id;
        }

        /** Queue outcomes: a String reply text, or an AiProviderException. Last outcome repeats. */
        ScriptedProvider on(String model, Object... outcomes) {
            script.put(model, new ArrayDeque<>(List.of(outcomes)));
            return this;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean configured() {
            return configured;
        }

        @Override
        public AiReply generate(String model, AiPrompt prompt, AiGenerationSettings settings) {
            calls.add(model + (prompt.user().startsWith("REPAIR") ? " (repair)" : ""));
            Deque<Object> q = script.get(model);
            Object next = q == null || q.isEmpty() ? new AiProviderException(AiFailureType.MODEL_NOT_FOUND, "unscripted")
                    : q.size() > 1 ? q.poll() : q.peek();
            if (next instanceof AiProviderException e) {
                throw e;
            }
            return new AiReply((String) next, 100, 50);
        }
    }

    static AiProviderException fail(AiFailureType type) {
        return new AiProviderException(type, "simulated " + type);
    }

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-04T10:00:00Z");

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    private ScriptedProvider gemini;
    private ScriptedProvider groq;
    private MutableClock clock;
    private final List<Duration> sleeps = new ArrayList<>();
    private final Map<String, AiModelHealth> healthTable = new HashMap<>();
    private AiModelRouter router;
    private AiModelHealthService health;

    private static final AiGenerationSettings SETTINGS = new AiGenerationSettings(0.2, 1000, "high", true);
    private static final AiPrompt PROMPT = new AiPrompt("system", "review");

    @BeforeEach
    void setUp() {
        gemini = new ScriptedProvider("gemini");
        groq = new ScriptedProvider("groq");
        clock = new MutableClock();
        AiProperties properties = new AiProperties(List.of("gemini", "groq"),
                new AiProperties.Provider(true, "k", "", List.of("gemini-a", "gemini-b"), Duration.ofMinutes(5)),
                new AiProperties.Provider(true, "k", "", List.of("groq-a"), Duration.ofMinutes(5)),
                new AiProperties.Generation(0.2, 1000, "high"),
                new AiProperties.Retry(2, Duration.ofSeconds(1), Duration.ofSeconds(20)),
                new AiProperties.Cooldown(Duration.ofMinutes(1), Duration.ofMinutes(30), Duration.ofHours(6)));
        // An in-memory stand-in for the ai_model_health table.
        AiModelHealthRepository repository = mock(AiModelHealthRepository.class);
        when(repository.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(healthTable.get(inv.<String>getArgument(0))));
        when(repository.save(any())).thenAnswer(inv -> {
            AiModelHealth h = inv.getArgument(0);
            healthTable.put(AiModelHealth.key(h.getProvider(), h.getModel()), h);
            return h;
        });
        health = new AiModelHealthService(repository, properties, List.of(gemini, groq), clock);
        router = new AiModelRouter(List.of(gemini, groq), properties, health, sleeps::add, clock);
    }

    private AiModelRouter.Routed<String> route() {
        return router.generate(PROMPT, SETTINGS, text -> {
            if (!text.equals(OK)) {
                throw new InvalidAiOutputException("not the expected JSON");
            }
            return text;
        }, problem -> new AiPrompt("system", "REPAIR: " + problem));
    }

    private AiModelHealth healthOf(String provider, String model) {
        return health.get(provider, model);
    }

    @Test
    void geminiIsUsedFirstAndNoFallbackWhenItSucceeds() {
        gemini.on("gemini-a", OK);
        AiModelRouter.Routed<String> r = route();
        assertThat(r.provider()).isEqualTo("gemini");
        assertThat(r.model()).isEqualTo("gemini-a");
        assertThat(r.fallbackUsed()).isFalse();
        assertThat(r.fallbackReason()).isNull();
        assertThat(r.attempts()).isEqualTo(1);
        assertThat(groq.calls).isEmpty();
    }

    @Test
    void rateLimitIsRetriedOnTheSameModelWithBackoff() {
        gemini.on("gemini-a", fail(AiFailureType.RATE_LIMITED), fail(AiFailureType.UNAVAILABLE), OK);
        AiModelRouter.Routed<String> r = route();
        assertThat(r.model()).isEqualTo("gemini-a");
        assertThat(r.attempts()).isEqualTo(3);
        assertThat(sleeps).hasSize(2);
        // ~1s then ~2s, each with ±25% jitter
        assertThat(sleeps.get(0)).isBetween(Duration.ofMillis(750), Duration.ofMillis(1250));
        assertThat(sleeps.get(1)).isBetween(Duration.ofMillis(1500), Duration.ofMillis(2500));
        assertThat(healthOf("gemini", "gemini-a").getState()).isEqualTo(AiModelState.HEALTHY);
    }

    @Test
    void geminiChainExhaustedFallsBackToGroqThenGeminiRecoversAfterCooldown() {
        gemini.on("gemini-a", fail(AiFailureType.RATE_LIMITED)).on("gemini-b", fail(AiFailureType.QUOTA_EXCEEDED));
        groq.on("groq-a", OK);

        AiModelRouter.Routed<String> first = route();
        assertThat(gemini.calls).containsExactly("gemini-a", "gemini-a", "gemini-a", "gemini-b", "gemini-b", "gemini-b");
        assertThat(first.provider()).isEqualTo("groq");
        assertThat(first.fallbackUsed()).isTrue();
        assertThat(first.fallbackReason()).isEqualTo("GEMINI_RATE_LIMITED");
        assertThat(first.attempts()).isEqualTo(7);
        assertThat(healthOf("gemini", "gemini-a").getState()).isEqualTo(AiModelState.COOLDOWN);
        assertThat(healthOf("gemini", "gemini-a").getCooldownUntil()).isEqualTo(clock.now.plus(Duration.ofMinutes(1)));

        // Within the cooldown: Gemini isn't called at all; Groq serves.
        gemini.calls.clear();
        clock.now = clock.now.plusSeconds(30);
        AiModelRouter.Routed<String> second = route();
        assertThat(gemini.calls).isEmpty();
        assertThat(second.provider()).isEqualTo("groq");
        assertThat(second.fallbackReason()).isEqualTo("GEMINI_COOLDOWN");

        // Cooldown expired and Gemini works again: it is tried first and becomes primary again.
        clock.now = clock.now.plus(Duration.ofMinutes(2));
        gemini.on("gemini-a", OK);
        AiModelRouter.Routed<String> third = route();
        assertThat(third.provider()).isEqualTo("gemini");
        assertThat(third.model()).isEqualTo("gemini-a");
        assertThat(third.fallbackUsed()).isFalse();
        AiModelHealth recovered = healthOf("gemini", "gemini-a");
        assertThat(recovered.getState()).isEqualTo(AiModelState.HEALTHY);
        assertThat(recovered.getFailureCount()).isZero();
        assertThat(recovered.getCooldownUntil()).isNull();
    }

    @Test
    void repeatedFailureAfterCooldownExtendsIt() {
        gemini.on("gemini-a", fail(AiFailureType.UNAVAILABLE)).on("gemini-b", OK);
        route();
        assertThat(healthOf("gemini", "gemini-a").getCooldownUntil()).isEqualTo(clock.now.plus(Duration.ofMinutes(1)));

        clock.now = clock.now.plus(Duration.ofMinutes(2)); // expired: Gemini A is tried again, fails again
        route();
        assertThat(healthOf("gemini", "gemini-a").getCooldownUntil()).isEqualTo(clock.now.plus(Duration.ofMinutes(2)));
        assertThat(healthOf("gemini", "gemini-a").getFailureCount()).isEqualTo(2);
    }

    @Test
    void invalidKeyStopsUsingThatProviderWithoutRetriesOrEndlessFallback() {
        gemini.on("gemini-a", fail(AiFailureType.AUTH_INVALID));
        groq.on("groq-a", OK);

        AiModelRouter.Routed<String> r = route();
        assertThat(gemini.calls).containsExactly("gemini-a"); // not retried, gemini-b not tried with the same bad key
        assertThat(sleeps).isEmpty();
        assertThat(r.provider()).isEqualTo("groq");
        assertThat(healthOf("gemini", "gemini-a").getState()).isEqualTo(AiModelState.CONFIGURATION_ERROR);
        assertThat(healthOf("gemini", "gemini-b").getState()).isEqualTo(AiModelState.CONFIGURATION_ERROR);

        gemini.calls.clear();
        route();
        assertThat(gemini.calls).isEmpty();
    }

    @Test
    void unknownModelIsSkippedAndTheNextGeminiModelIsUsed() {
        gemini.on("gemini-a", fail(AiFailureType.MODEL_NOT_FOUND)).on("gemini-b", OK);
        AiModelRouter.Routed<String> r = route();
        assertThat(r.model()).isEqualTo("gemini-b");
        assertThat(r.fallbackUsed()).isTrue();
        assertThat(r.fallbackReason()).isEqualTo("GEMINI_MODEL_NOT_FOUND");
        assertThat(healthOf("gemini", "gemini-a").getState()).isEqualTo(AiModelState.CONFIGURATION_ERROR);
        assertThat(healthOf("gemini", "gemini-b").getState()).isEqualTo(AiModelState.HEALTHY);
    }

    @Test
    void ourOwnBadRequestStopsRoutingAndBlamesNoModel() {
        gemini.on("gemini-a", fail(AiFailureType.REQUEST_INVALID));
        groq.on("groq-a", OK);
        assertThatThrownBy(this::route)
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> assertThat(e.code()).isEqualTo("AI_REQUEST_REJECTED"));
        assertThat(groq.calls).isEmpty();
        assertThat(healthOf("gemini", "gemini-a").getState()).isEqualTo(AiModelState.HEALTHY);
    }

    @Test
    void invalidOutputGetsExactlyOneRepairOnTheSameModel() {
        gemini.on("gemini-a", "not json", OK);
        AiModelRouter.Routed<String> r = route();
        assertThat(gemini.calls).containsExactly("gemini-a", "gemini-a (repair)");
        assertThat(r.model()).isEqualTo("gemini-a");
        assertThat(r.attempts()).isEqualTo(2);
    }

    @Test
    void stillInvalidAfterRepairMovesOnWithoutHealthPenalty() {
        gemini.on("gemini-a", "not json").on("gemini-b", OK);
        AiModelRouter.Routed<String> r = route();
        assertThat(gemini.calls).containsExactly("gemini-a", "gemini-a (repair)", "gemini-b");
        assertThat(r.model()).isEqualTo("gemini-b");
        assertThat(r.fallbackReason()).isEqualTo("GEMINI_INVALID_OUTPUT");
        assertThat(healthOf("gemini", "gemini-a").getState()).isEqualTo(AiModelState.HEALTHY);
    }

    @Test
    void cutOffOutputAlsoGetsOneRepair() {
        gemini.on("gemini-a", fail(AiFailureType.EMPTY_RESPONSE), OK);
        assertThat(route().model()).isEqualTo("gemini-a");
        assertThat(gemini.calls).containsExactly("gemini-a", "gemini-a (repair)");
    }

    @Test
    void contextTooLargeForAModelSkipsItWithoutHealthPenalty() {
        gemini.configured = false;
        groq.on("groq-a", fail(AiFailureType.CONTEXT_TOO_LARGE));
        assertThatThrownBy(this::route)
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> assertThat(e.code()).isEqualTo("AI_UNAVAILABLE"));
        assertThat(healthOf("groq", "groq-a").getState()).isEqualTo(AiModelState.HEALTHY);
    }

    @Test
    void longProviderRetryAfterMeansCoolDownNowRatherThanWait() {
        gemini.on("gemini-a", new AiProviderException(AiFailureType.QUOTA_EXCEEDED, "daily quota", Duration.ofHours(3)))
                .on("gemini-b", OK);
        route();
        assertThat(sleeps).isEmpty();
        assertThat(healthOf("gemini", "gemini-a").getCooldownUntil()).isEqualTo(clock.now.plus(Duration.ofHours(3)));
    }

    @Test
    void groqOnlyModeWorksWhenGeminiHasNoKey() {
        gemini.configured = false;
        groq.on("groq-a", OK);
        AiModelRouter.Routed<String> r = route();
        assertThat(r.provider()).isEqualTo("groq");
        assertThat(r.fallbackUsed()).isFalse(); // Groq is the first *configured* model
    }

    @Test
    void noConfiguredProviderIsAClearConfigurationError() {
        gemini.configured = false;
        groq.configured = false;
        assertThatThrownBy(this::route)
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> assertThat(e.code()).isEqualTo("AI_NOT_CONFIGURED"));
    }

    @Test
    void everythingUnavailableFailsClearly() {
        gemini.on("gemini-a", fail(AiFailureType.SERVER_ERROR)).on("gemini-b", fail(AiFailureType.TIMEOUT));
        groq.on("groq-a", fail(AiFailureType.NETWORK));
        assertThatThrownBy(this::route)
                .isInstanceOfSatisfying(AiUnavailableException.class, e -> assertThat(e.code()).isEqualTo("AI_UNAVAILABLE"));
        assertThat(healthOf("groq", "groq-a").getState()).isEqualTo(AiModelState.COOLDOWN);
    }

    @Test
    void startupClearsConfigurationErrorsAndDisablesUnconfiguredProviders() {
        gemini.on("gemini-a", fail(AiFailureType.AUTH_INVALID));
        groq.on("groq-a", OK);
        route();
        groq.configured = false;

        health.syncWithConfiguration(); // e.g. after the key was fixed and the server restarted

        assertThat(healthOf("gemini", "gemini-a").getState()).isEqualTo(AiModelState.HEALTHY);
        assertThat(healthOf("groq", "groq-a").getState()).isEqualTo(AiModelState.DISABLED);
    }
}
