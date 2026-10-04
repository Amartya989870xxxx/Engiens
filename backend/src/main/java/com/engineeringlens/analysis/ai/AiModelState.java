package com.engineeringlens.analysis.ai;

public enum AiModelState {
    /** Eligible. */
    HEALTHY,
    /** Failed repeatedly with transient errors; skipped until cooldownUntil, then tried again. */
    COOLDOWN,
    /** Bad key or unknown model: skipped until rechecked (after a delay, or on restart). */
    CONFIGURATION_ERROR,
    /** Provider disabled or has no key. */
    DISABLED
}
