package com.engineeringlens.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * At most {@code max} events per key in a fixed window. In memory, so limits reset when the server restarts and
 * aren't shared between instances: fine for a single-server deployment, and the reason it needs no new
 * infrastructure. Old windows are dropped as the map grows, so memory stays bounded.
 */
final class RateLimiter {

    private static final int PRUNE_ABOVE = 10_000;

    private record Window(Instant start, int count) {
    }

    private final int max;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    RateLimiter(int max, Duration window, Clock clock) {
        this.max = max;
        this.window = window;
        this.clock = clock;
    }

    /** How long until another event is allowed for this key; zero when it is allowed now. */
    Duration retryAfter(String key) {
        Instant now = clock.instant();
        Window w = windows.get(key);
        if (w == null || !now.isBefore(w.start().plus(window)) || w.count() < max) {
            return Duration.ZERO;
        }
        return Duration.between(now, w.start().plus(window));
    }

    void record(String key) {
        Instant now = clock.instant();
        windows.compute(key, (k, w) -> w == null || !now.isBefore(w.start().plus(window)) ? new Window(now, 1) : new Window(w.start(), w.count() + 1));
        if (windows.size() > PRUNE_ABOVE) {
            windows.values().removeIf(w -> !now.isBefore(w.start().plus(window)));
        }
    }

    void reset(String key) {
        windows.remove(key);
    }
}
