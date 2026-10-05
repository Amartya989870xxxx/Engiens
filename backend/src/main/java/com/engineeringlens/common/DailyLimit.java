package com.engineeringlens.common;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * Caps on actions that spend shared AI quota (reviews, Scenario Labs), per user per rolling 24 hours. Engiens runs on
 * free provider quotas shared by every user; without a cap, one user could use them up for everyone.
 */
public final class DailyLimit {

    public static final Duration WINDOW = Duration.ofHours(24);

    private DailyLimit() {
    }

    /**
     * Refuses when {@code recent} (the actions that count, oldest first, from the last 24 hours) has reached
     * {@code limit}. A limit of zero or less turns the cap off.
     */
    public static void check(List<Instant> recent, int limit, String what, Instant now) {
        if (limit <= 0 || recent.size() < limit) {
            return;
        }
        Instant freesUp = recent.get(recent.size() - limit).plus(WINDOW);
        long hours = Math.max(1, (Duration.between(now, freesUp).toMinutes() + 59) / 60);
        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "DAILY_LIMIT_REACHED", "You've started " + limit + " " + what
                + " in the last 24 hours, the most this server allows while it runs on free AI quotas. You can start another in about "
                + hours + (hours == 1 ? " hour." : " hours."));
    }
}
