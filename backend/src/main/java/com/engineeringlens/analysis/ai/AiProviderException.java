package com.engineeringlens.analysis.ai;

import java.time.Duration;

/**
 * A classified provider failure. The message is sanitised: it never contains API keys, prompts or
 * source code, only a short description safe to log and store.
 */
public class AiProviderException extends RuntimeException {

    private final AiFailureType type;
    private final Duration retryAfter;

    public AiProviderException(AiFailureType type, String sanitizedMessage, Duration retryAfter) {
        super(sanitizedMessage);
        this.type = type;
        this.retryAfter = retryAfter;
    }

    public AiProviderException(AiFailureType type, String sanitizedMessage) {
        this(type, sanitizedMessage, null);
    }

    public AiFailureType type() {
        return type;
    }

    /** What the provider asked us to wait, if it said. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
