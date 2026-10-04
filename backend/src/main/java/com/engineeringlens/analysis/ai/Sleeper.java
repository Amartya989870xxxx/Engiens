package com.engineeringlens.analysis.ai;

import java.time.Duration;

/** Waiting between retries, behind an interface so tests don't actually sleep. */
@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration);

    static Sleeper real() {
        return d -> {
            try {
                Thread.sleep(d.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting to retry", e);
            }
        };
    }
}
