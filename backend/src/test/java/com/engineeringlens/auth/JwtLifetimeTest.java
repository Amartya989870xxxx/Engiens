package com.engineeringlens.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import com.engineeringlens.user.User;

/**
 * A session lasts exactly the configured TTL from login, with the real encoder and decoder beans: an idle user is
 * never logged out early, a restart with the same secret keeps sessions, and only expiry or another secret ends one.
 */
class JwtLifetimeTest {

    private static final String SECRET = "test-secret-at-least-thirty-two-bytes-long";
    private final SecurityConfig config = new SecurityConfig();
    private final SecurityConfig.JwtSettings settings = config.jwtSettings(SECRET, 120);
    private final JwtEncoder encoder = config.jwtEncoder(settings);
    private final JwtDecoder decoder = config.jwtDecoder(settings);

    @Test
    void aTokenIsIssuedForExactlyTheConfiguredLifetime() {
        Jwt jwt = decoder.decode(new JwtService(encoder, settings).issue(new User("a@b.com", "hash", "Asha")));
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(120));
    }

    @Test
    void aTokenNearTheEndOfItsLifetimeIsStillAccepted() {
        Instant now = Instant.now();
        Jwt jwt = decoder.decode(token(now.minus(Duration.ofMinutes(119)), now.plusSeconds(30)));
        assertThat(jwt.getSubject()).isEqualTo("user-1");
    }

    @Test
    void anExpiredTokenIsRejectedOnceTheClockSkewAllowanceHasPassed() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> decoder.decode(token(now.minus(Duration.ofMinutes(125)), now.minus(Duration.ofMinutes(5)))))
                .isInstanceOf(JwtValidationException.class).hasMessageContaining("expired");
    }

    @Test
    void aRestartWithTheSameSecretKeepsSessionsButANewSecretEndsThem() {
        Instant now = Instant.now();
        String token = token(now, now.plus(Duration.ofMinutes(120)));

        SecurityConfig restarted = new SecurityConfig();
        assertThat(restarted.jwtDecoder(restarted.jwtSettings(SECRET, 120)).decode(token).getSubject()).isEqualTo("user-1");

        JwtDecoder otherSecret = restarted.jwtDecoder(restarted.jwtSettings("a-different-secret-of-thirty-two-bytes!", 120));
        assertThatThrownBy(() -> otherSecret.decode(token)).isInstanceOf(BadJwtException.class);
    }

    private String token(Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder().subject("user-1").issuedAt(issuedAt).expiresAt(expiresAt).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
