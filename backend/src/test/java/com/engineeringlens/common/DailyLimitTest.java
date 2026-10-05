package com.engineeringlens.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class DailyLimitTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    @Test
    void refusesOnceTheLimitIsReachedAndSaysWhenTheOldestFreesUp() {
        List<Instant> two = List.of(NOW.minus(Duration.ofHours(20)), NOW.minus(Duration.ofHours(1)));
        assertThatCode(() -> DailyLimit.check(two.subList(0, 1), 2, "reviews", NOW)).doesNotThrowAnyException();
        assertThatThrownBy(() -> DailyLimit.check(two, 2, "reviews", NOW))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("DAILY_LIMIT_REACHED");
                    assertThat(e.getMessage()).isEqualTo("You've started 2 reviews in the last 24 hours, "
                            + "the most this server allows while it runs on free AI quotas. You can start another in about 4 hours.");
                });
    }

    @Test
    void zeroMeansNoLimit() {
        assertThatCode(() -> DailyLimit.check(List.of(NOW, NOW, NOW), 0, "reviews", NOW)).doesNotThrowAnyException();
    }
}
