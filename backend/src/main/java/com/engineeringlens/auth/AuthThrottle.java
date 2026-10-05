package com.engineeringlens.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.engineeringlens.common.ApiException;

/**
 * Slows down password guessing and mass registration. Per IP, every login attempt counts; per email, only failed
 * ones (a successful login clears them), so an attacker can't hammer one account from many addresses, and a user
 * who mistypes a few times isn't punished after logging in. Behind the reverse proxy the client IP is the real one
 * (the production profile trusts the proxy's forwarded headers).
 */
@Component
public class AuthThrottle {

    private final RateLimiter loginPerIp;
    private final RateLimiter failuresPerEmail;
    private final RateLimiter registerPerIp;

    @Autowired
    public AuthThrottle(@Value("${app.auth.limits.login-per-ip:20}") int loginPerIp,
            @Value("${app.auth.limits.login-failures-per-email:5}") int failuresPerEmail,
            @Value("${app.auth.limits.login-window:PT15M}") Duration loginWindow,
            @Value("${app.auth.limits.register-per-ip:5}") int registerPerIp,
            @Value("${app.auth.limits.register-window:PT1H}") Duration registerWindow) {
        this(loginPerIp, failuresPerEmail, loginWindow, registerPerIp, registerWindow, Clock.systemUTC());
    }

    AuthThrottle(int loginPerIp, int failuresPerEmail, Duration loginWindow, int registerPerIp, Duration registerWindow, Clock clock) {
        this.loginPerIp = new RateLimiter(loginPerIp, loginWindow, clock);
        this.failuresPerEmail = new RateLimiter(failuresPerEmail, loginWindow, clock);
        this.registerPerIp = new RateLimiter(registerPerIp, registerWindow, clock);
    }

    /** Before checking a password. Counts the attempt against the IP. */
    void beforeLogin(String ip, String email) {
        Duration byIp = loginPerIp.retryAfter(ip);
        Duration byEmail = failuresPerEmail.retryAfter(key(email));
        refuseIfLimited(byIp.compareTo(byEmail) >= 0 ? byIp : byEmail, "login attempts");
        loginPerIp.record(ip);
    }

    void loginFailed(String email) {
        failuresPerEmail.record(key(email));
    }

    void loginSucceeded(String email) {
        failuresPerEmail.reset(key(email));
    }

    void beforeRegister(String ip) {
        refuseIfLimited(registerPerIp.retryAfter(ip), "sign-ups from this network");
        registerPerIp.record(ip);
    }

    private static void refuseIfLimited(Duration wait, String what) {
        if (!wait.isZero()) {
            long minutes = Math.max(1, (wait.getSeconds() + 59) / 60);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                    "Too many " + what + ". Try again in " + minutes + (minutes == 1 ? " minute." : " minutes."));
        }
    }

    private static String key(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }
}
