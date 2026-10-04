package com.engineeringlens.analysis.ai;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Per-model health, persisted so a restart doesn't forget an active cooldown. No keys, no responses. */
@Entity
@Table(name = "ai_model_health")
public class AiModelHealth {

    @Id
    private String id;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AiModelState state;

    @Column(name = "last_failure_type")
    private String lastFailureType;

    @Column(name = "last_failure_message")
    private String lastFailureMessage;

    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "cooldown_until")
    private Instant cooldownUntil;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiModelHealth() {
    }

    AiModelHealth(String provider, String model, Instant now) {
        this.id = key(provider, model);
        this.provider = provider;
        this.model = model;
        this.state = AiModelState.HEALTHY;
        this.updatedAt = now;
    }

    static String key(String provider, String model) {
        return provider + ":" + model;
    }

    /** Eligible now: healthy, or a cooldown/config-error recheck time that has passed. */
    boolean eligibleAt(Instant now) {
        return switch (state) {
            case HEALTHY -> true;
            case COOLDOWN, CONFIGURATION_ERROR -> cooldownUntil == null || !now.isBefore(cooldownUntil);
            case DISABLED -> false;
        };
    }

    void succeeded(Instant now) {
        state = AiModelState.HEALTHY;
        failureCount = 0;
        cooldownUntil = null;
        lastSuccessAt = now;
        updatedAt = now;
    }

    void failed(AiModelState newState, AiFailureType type, String message, Instant until, Instant now) {
        state = newState;
        failureCount++;
        lastFailureType = type.name();
        lastFailureMessage = message == null || message.length() <= 300 ? message : message.substring(0, 300);
        lastFailureAt = now;
        cooldownUntil = until;
        updatedAt = now;
    }

    void reset(AiModelState newState, Instant now) {
        state = newState;
        cooldownUntil = null;
        updatedAt = now;
    }

    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public AiModelState getState() { return state; }
    public String getLastFailureType() { return lastFailureType; }
    public int getFailureCount() { return failureCount; }
    public Instant getCooldownUntil() { return cooldownUntil; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
}
