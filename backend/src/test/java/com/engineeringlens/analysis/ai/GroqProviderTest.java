package com.engineeringlens.analysis.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

class GroqProviderTest {

    private static final String URL = "https://groq.test/openai/v1/chat/completions";
    private static final AiGenerationSettings SETTINGS = new AiGenerationSettings(0.2, 32768, "high", true);
    private static final AiPrompt PROMPT = new AiPrompt("sys", "user");

    private MockRestServiceServer server;
    private GroqProvider groq;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://groq.test/openai/v1");
        server = MockRestServiceServer.bindTo(builder).build();
        groq = new GroqProvider(new AiProperties.Provider(true, "gsk_test", "https://groq.test/openai/v1",
                List.of("openai/gpt-oss-120b"), Duration.ofMinutes(5)), builder, new ObjectMapper());
    }

    private AiProviderException failure(HttpStatus status, String body, HttpHeaders headers) {
        server.reset();
        server.expect(requestTo(URL)).andRespond(withStatus(status).headers(headers).contentType(MediaType.APPLICATION_JSON).body(body));
        try {
            groq.generate("openai/gpt-oss-120b", PROMPT, SETTINGS);
        } catch (AiProviderException e) {
            return e;
        }
        throw new AssertionError("expected a failure");
    }

    @Test
    void translatesThinkingIntoReasoningEffortForGptOss() {
        server.expect(requestTo(URL))
                .andExpect(header("Authorization", "Bearer gsk_test"))
                .andExpect(content().json("""
                        {"model":"openai/gpt-oss-120b","temperature":0.2,"max_completion_tokens":32768,
                         "response_format":{"type":"json_object"},"reasoning_effort":"high",
                         "messages":[{"role":"system","content":"sys"},{"role":"user","content":"user"}]}"""))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"{\\"ok\\":true}"},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":900,"completion_tokens":120}}""", MediaType.APPLICATION_JSON));
        AiReply reply = groq.generate("openai/gpt-oss-120b", PROMPT, SETTINGS);
        assertThat(reply.text()).isEqualTo("{\"ok\":true}");
        assertThat(reply.inputTokens()).isEqualTo(900);
    }

    @Test
    void rateLimitHonoursRetryAfterHeader() {
        HttpHeaders h = new HttpHeaders();
        h.add("retry-after", "7");
        AiProviderException e = failure(HttpStatus.TOO_MANY_REQUESTS,
                "{\"error\":{\"message\":\"Rate limit reached for requests per minute\",\"code\":\"rate_limit_exceeded\"}}", h);
        assertThat(e.type()).isEqualTo(AiFailureType.RATE_LIMITED);
        assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void requestsBiggerThanTheTokenAllowanceAreTooLargeNotRateLimited() {
        assertThat(failure(HttpStatus.TOO_MANY_REQUESTS, "{\"error\":{\"message\":\"Request too large for model on tokens per minute (TPM): Limit 8000, Requested 60000\"}}",
                new HttpHeaders()).type()).isEqualTo(AiFailureType.CONTEXT_TOO_LARGE);
        assertThat(failure(HttpStatus.PAYLOAD_TOO_LARGE, "{}", new HttpHeaders()).type()).isEqualTo(AiFailureType.CONTEXT_TOO_LARGE);
    }

    @Test
    void credentialsAndModelProblemsAreConfigurationErrors() {
        assertThat(failure(HttpStatus.UNAUTHORIZED, "{\"error\":{\"message\":\"Invalid API Key\",\"code\":\"invalid_api_key\"}}",
                new HttpHeaders()).type()).isEqualTo(AiFailureType.AUTH_INVALID);
        assertThat(failure(HttpStatus.NOT_FOUND, "{\"error\":{\"code\":\"model_not_found\"}}", new HttpHeaders()).type())
                .isEqualTo(AiFailureType.MODEL_NOT_FOUND);
        assertThat(failure(HttpStatus.BAD_REQUEST, "{\"error\":{\"code\":\"model_decommissioned\"}}", new HttpHeaders()).type())
                .isEqualTo(AiFailureType.MODEL_NOT_FOUND);
    }

    @Test
    void failedJsonGenerationIsAnOutputProblem() {
        assertThat(failure(HttpStatus.BAD_REQUEST, "{\"error\":{\"code\":\"json_validate_failed\"}}", new HttpHeaders()).type())
                .isEqualTo(AiFailureType.EMPTY_RESPONSE);
    }
}
