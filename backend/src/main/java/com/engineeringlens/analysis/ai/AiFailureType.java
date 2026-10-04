package com.engineeringlens.analysis.ai;

/**
 * Why a provider call failed, and therefore what the router should do about it. The classification is
 * the heart of fallback: retrying a quota error is right; retrying our own malformed request is not.
 */
public enum AiFailureType {
    /** 429: too many requests right now. Retry with backoff, then cool the model down. */
    RATE_LIMITED(true),
    /** 429 with quota exhaustion: may last until the quota resets. Retry briefly, then cool down. */
    QUOTA_EXCEEDED(true),
    /** 408/504 or a read timeout. */
    TIMEOUT(true),
    /** 500/502 from the provider. */
    SERVER_ERROR(true),
    /** 503: model overloaded or temporarily unavailable. */
    UNAVAILABLE(true),
    /** Connection refused/reset before any response. */
    NETWORK(true),
    /** 404 or "model not found/deprecated": skip this model, try the next. Not retried. */
    MODEL_NOT_FOUND(false),
    /** Invalid or unauthorised credentials: the whole provider is misconfigured. Not retried. */
    AUTH_INVALID(false),
    /** 400 for any other reason: most likely our bug. Stop routing; don't blame the model. */
    REQUEST_INVALID(false),
    /** The prompt doesn't fit this model's limits (413, or token-per-minute caps). Try the next model. */
    CONTEXT_TOO_LARGE(false),
    /** A 200 response with no usable text (e.g. blocked or empty). Treated like invalid output. */
    EMPTY_RESPONSE(false);

    private final boolean retryable;

    AiFailureType(boolean retryable) {
        this.retryable = retryable;
    }

    /** Worth retrying the same model after a pause. */
    public boolean retryable() {
        return retryable;
    }
}
