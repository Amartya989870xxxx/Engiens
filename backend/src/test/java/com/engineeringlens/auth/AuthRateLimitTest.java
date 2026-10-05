package com.engineeringlens.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Through HTTP with real (low) limits: guessing a password and mass sign-up are refused with 429. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = { "app.auth.limits.login-per-ip=50", "app.auth.limits.login-failures-per-email=3",
        "app.auth.limits.register-per-ip=3" })
class AuthRateLimitTest {

    @Autowired
    MockMvc mvc;

    private ResultActions register(String email, String ip) throws Exception {
        return mvc.perform(post("/api/auth/register").with(r -> { r.setRemoteAddr(ip); return r; }).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Asha\",\"email\":\"" + email + "\",\"password\":\"password123\"}"));
    }

    private ResultActions login(String email, String password, String ip) throws Exception {
        return mvc.perform(post("/api/auth/login").with(r -> { r.setRemoteAddr(ip); return r; }).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    @Test
    void repeatedWrongPasswordsLockThatAccountsLoginForAWhileEvenFromNewAddresses() throws Exception {
        register("limit-login@example.com", "10.0.0.1").andExpect(status().isCreated());
        for (int i = 0; i < 3; i++) {
            login("limit-login@example.com", "wrong-password", "10.0.1." + i).andExpect(status().isUnauthorized());
        }
        login("limit-login@example.com", "password123", "10.0.2.1")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"))
                .andExpect(jsonPath("$.message").value("Too many login attempts. Try again in 15 minutes."));
    }

    @Test
    void aCorrectLoginClearsEarlierMistakes() throws Exception {
        register("limit-ok@example.com", "10.0.3.1").andExpect(status().isCreated());
        login("limit-ok@example.com", "wrong-password", "10.0.3.2").andExpect(status().isUnauthorized());
        login("limit-ok@example.com", "wrong-password", "10.0.3.2").andExpect(status().isUnauthorized());
        login("limit-ok@example.com", "password123", "10.0.3.2").andExpect(status().isOk());
        login("limit-ok@example.com", "wrong-password", "10.0.3.2").andExpect(status().isUnauthorized());
        login("limit-ok@example.com", "password123", "10.0.3.2").andExpect(status().isOk());
    }

    @Test
    void oneAddressCanOnlyCreateAFewAccountsAnHour() throws Exception {
        for (int i = 0; i < 3; i++) {
            register("limit-signup-" + i + "@example.com", "10.0.4.1").andExpect(status().isCreated());
        }
        register("limit-signup-3@example.com", "10.0.4.1").andExpect(status().isTooManyRequests());
        register("limit-signup-3@example.com", "10.0.4.2").andExpect(status().isCreated());
    }
}
