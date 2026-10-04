package com.engineeringlens.analysis.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Tracks whether each model may be tried. There is no separate "quota check": the next real request
 * after a cooldown expires is the health check, so no quota is spent probing.
 */
@Service
public class AiModelHealthService {

    private static final Logger log = LoggerFactory.getLogger(AiModelHealthService.class);
    /** A daily quota can ask for a very long wait; never cool down longer than this. */
    private static final Duration LONGEST_COOLDOWN = Duration.ofHours(24);

    private final AiModelHealthRepository repository;
    private final AiProperties properties;
    private final List<AiProvider> providers;
    private final Clock clock;

    public AiModelHealthService(AiModelHealthRepository repository, AiProperties properties, List<AiProvider> providers, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.providers = providers;
        this.clock = clock;
    }

    AiModelHealth get(String provider, String model) {
        return repository.findById(AiModelHealth.key(provider, model))
                .orElseGet(() -> new AiModelHealth(provider, model, clock.instant()));
    }

    boolean eligible(String provider, String model) {
        return get(provider, model).eligibleAt(clock.instant());
    }

    void recordSuccess(String provider, String model) {
        AiModelHealth h = get(provider, model);
        boolean recovered = h.getState() != AiModelState.HEALTHY;
        h.succeeded(clock.instant());
        repository.save(h);
        if (recovered) {
            log.info("AI model recovered: {}:{}", provider, model);
        }
    }

    /**
     * Transient failure after retries: cool down, doubling with each consecutive failure, but never
     * shorter than what the provider asked for (capped at a day).
     */
    void recordCooldown(String provider, String model, AiProviderException e) {
        AiModelHealth h = get(provider, model);
        Duration base = properties.cooldown().initial().multipliedBy(1L << Math.min(h.getFailureCount(), 10));
        Duration wait = base.compareTo(properties.cooldown().max()) > 0 ? properties.cooldown().max() : base;
        if (e.retryAfter() != null && e.retryAfter().compareTo(wait) > 0) {
            wait = e.retryAfter().compareTo(LONGEST_COOLDOWN) > 0 ? LONGEST_COOLDOWN : e.retryAfter();
        }
        Instant now = clock.instant();
        h.failed(AiModelState.COOLDOWN, e.type(), e.getMessage(), now.plus(wait), now);
        repository.save(h);
        log.warn("AI model cooling down: {}:{} type={} for={}s", provider, model, e.type(), wait.toSeconds());
    }

    /** Bad key or unknown model: skip until rechecked, rather than burning retries on it. */
    void recordConfigurationError(String provider, String model, AiProviderException e) {
        AiModelHealth h = get(provider, model);
        Instant now = clock.instant();
        h.failed(AiModelState.CONFIGURATION_ERROR, e.type(), e.getMessage(), now.plus(properties.cooldown().configErrorRecheck()), now);
        repository.save(h);
        log.warn("AI model misconfigured: {}:{} type={}", provider, model, e.type());
    }

    /**
     * On startup the configuration may have changed (new key, new model list), so configuration errors
     * are cleared and models of unconfigured providers are marked DISABLED. Cooldowns are kept.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void syncWithConfiguration() {
        Instant now = clock.instant();
        for (AiProvider provider : providers) {
            AiProperties.Provider config = properties.provider(provider.id());
            if (config == null) {
                continue;
            }
            for (String model : config.models()) {
                AiModelHealth h = get(provider.id(), model);
                if (!provider.configured()) {
                    h.reset(AiModelState.DISABLED, now);
                } else if (h.getState() == AiModelState.CONFIGURATION_ERROR || h.getState() == AiModelState.DISABLED) {
                    h.reset(AiModelState.HEALTHY, now);
                } else {
                    continue;
                }
                repository.save(h);
            }
            log.info("AI provider {}: {}", provider.id(), provider.configured() ? "configured, models " + config.models() : "not configured");
        }
    }
}
