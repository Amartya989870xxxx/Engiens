package com.engineeringlens.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.engineeringlens.common.ApiException;

/** Login and sign-up limits, with a clock the test controls. */
class AuthThrottleTest {

    private static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-10-06T10:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovableClock clock = new MovableClock();
    private final AuthThrottle throttle = new AuthThrottle(3, 2, Duration.ofMinutes(15), 2, Duration.ofHours(1), clock);

    @Test
    void failedLoginsForOneEmailAreLimitedAndASuccessClearsThem() {
        throttle.beforeLogin("1.1.1.1", "asha@example.com");
        throttle.loginFailed("ASHA@example.com "); // the same account, however it was typed
        throttle.beforeLogin("2.2.2.2", "asha@example.com");
        throttle.loginFailed("asha@example.com");

        assertThatThrownBy(() -> throttle.beforeLogin("3.3.3.3", "asha@example.com"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("TOO_MANY_ATTEMPTS");
                    assertThat(e.getMessage()).isEqualTo("Too many login attempts. Try again in 15 minutes.");
                });
        assertThatCode(() -> throttle.beforeLogin("3.3.3.3", "other@example.com")).doesNotThrowAnyException();

        clock.now = clock.now.plus(Duration.ofMinutes(15));
        assertThatCode(() -> throttle.beforeLogin("4.4.4.4", "asha@example.com")).doesNotThrowAnyException();
        throttle.loginFailed("asha@example.com");
        throttle.loginSucceeded("asha@example.com");
        throttle.loginFailed("asha@example.com");
        assertThatCode(() -> throttle.beforeLogin("5.5.5.5", "asha@example.com")).doesNotThrowAnyException();
    }

    @Test
    void everyLoginAttemptCountsAgainstTheAddress() {
        throttle.beforeLogin("9.9.9.9", "a@example.com");
        throttle.beforeLogin("9.9.9.9", "b@example.com");
        throttle.beforeLogin("9.9.9.9", "c@example.com");
        assertThatThrownBy(() -> throttle.beforeLogin("9.9.9.9", "d@example.com")).isInstanceOf(ApiException.class);
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        assertThatThrownBy(() -> throttle.beforeLogin("9.9.9.9", "d@example.com")).hasMessage("Too many login attempts. Try again in 5 minutes.");
    }

    @Test
    void signUpsAreLimitedPerAddress() {
        throttle.beforeRegister("7.7.7.7");
        throttle.beforeRegister("7.7.7.7");
        assertThatThrownBy(() -> throttle.beforeRegister("7.7.7.7")).hasMessage("Too many sign-ups from this network. Try again in 60 minutes.");
        assertThatCode(() -> throttle.beforeRegister("8.8.8.8")).doesNotThrowAnyException();
    }
}
