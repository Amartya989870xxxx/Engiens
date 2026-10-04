package com.engineeringlens.analysis.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.ObjectMapper;

class GeminiProviderTest {

    private static final String URL = "https://gemini.test/v1beta/models/gemini-3.8-flash:generateContent";
    private static final AiGenerationSettings SETTINGS = new AiGenerationSettings(0.2, 32768, "high", true);
    private static final AiPrompt PROMPT = new AiPrompt("You are a reviewer.", "Review this.");

    private MockRestServiceServer server;
    private GeminiProvider gemini;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://gemini.test/v1beta");
        server = MockRestServiceServer.bindTo(builder).build();
        AiProperties.Provider config = new AiProperties.Provider(true, "test-key", "https://gemini.test/v1beta",
                List.of("gemini-3.8-flash"), Duration.ofMinutes(5));
        gemini = new GeminiProvider(config, builder, new ObjectMapper());
    }

    private AiProviderException failure(HttpStatus status, String body) {
        server.reset();
        server.expect(requestTo(URL)).andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body));
        try {
            gemini.generate("gemini-3.8-flash", PROMPT, SETTINGS);
        } catch (AiProviderException e) {
            return e;
        }
        throw new AssertionError("expected a failure");
    }

    @Test
    void sendsAStrictJsonRequestWithThinkingAndKeyHeader() {
        server.expect(requestTo(URL))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(content().json("""
                        {"systemInstruction":{"parts":[{"text":"You are a reviewer."}]},
                         "contents":[{"role":"user","parts":[{"text":"Review this."}]}],
                         "generationConfig":{"maxOutputTokens":32768,"responseMimeType":"application/json",
                                             "thinkingConfig":{"thinkingLevel":"high"}}}""", true))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"parts":[{"text":"thinking...","thought":true},{"text":"{\\"ok\\":"},
                          {"text":"true}"}]},"finishReason":"STOP"}],
                         "usageMetadata":{"promptTokenCount":1200,"candidatesTokenCount":300,"thoughtsTokenCount":50}}""",
                        MediaType.APPLICATION_JSON));

        AiReply reply = gemini.generate("gemini-3.8-flash", PROMPT, SETTINGS);

        assertThat(reply.text()).isEqualTo("{\"ok\":true}"); // thought parts are not part of the answer
        assertThat(reply.inputTokens()).isEqualTo(1200);
        assertThat(reply.outputTokens()).isEqualTo(350);
    }

    @Test
    void invalidKeyIsAConfigurationErrorEvenThoughGeminiSays400() {
        AiProviderException e = failure(HttpStatus.BAD_REQUEST, """
                {"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT",
                 "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID"}]}}""");
        assertThat(e.type()).isEqualTo(AiFailureType.AUTH_INVALID);
    }

    @Test
    void quotaExhaustionCarriesGeminisRetryDelay() {
        AiProviderException e = failure(HttpStatus.TOO_MANY_REQUESTS, """
                {"error":{"code":429,"message":"You exceeded your current quota.","status":"RESOURCE_EXHAUSTED",
                 "details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"37s"}]}}""");
        assertThat(e.type()).isEqualTo(AiFailureType.QUOTA_EXCEEDED);
        assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(37));
    }

    @Test
    void statusCodesMapToFailureTypes() {
        assertThat(failure(HttpStatus.NOT_FOUND, "{\"error\":{\"code\":404,\"message\":\"models/x is not found\"}}").type())
                .isEqualTo(AiFailureType.MODEL_NOT_FOUND);
        assertThat(failure(HttpStatus.SERVICE_UNAVAILABLE, "{\"error\":{\"code\":503,\"message\":\"overloaded\"}}").type())
                .isEqualTo(AiFailureType.UNAVAILABLE);
        assertThat(failure(HttpStatus.INTERNAL_SERVER_ERROR, "oops").type()).isEqualTo(AiFailureType.SERVER_ERROR);
        assertThat(failure(HttpStatus.GATEWAY_TIMEOUT, "{}").type()).isEqualTo(AiFailureType.TIMEOUT);
        assertThat(failure(HttpStatus.TOO_MANY_REQUESTS, "{\"error\":{\"message\":\"Too many requests\"}}").type())
                .isEqualTo(AiFailureType.RATE_LIMITED);
        assertThat(failure(HttpStatus.BAD_REQUEST, "{\"error\":{\"message\":\"Invalid JSON payload\",\"status\":\"INVALID_ARGUMENT\"}}").type())
                .isEqualTo(AiFailureType.REQUEST_INVALID);
        assertThat(failure(HttpStatus.BAD_REQUEST, "{\"error\":{\"message\":\"The input token count exceeds the maximum number of tokens allowed\"}}").type())
                .isEqualTo(AiFailureType.CONTEXT_TOO_LARGE);
    }

    @Test
    void truncatedOrBlockedOutputIsAnEmptyResponse() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"half\\\":\"}]},\"finishReason\":\"MAX_TOKENS\"}]}",
                MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> gemini.generate("gemini-3.8-flash", PROMPT, SETTINGS))
                .isInstanceOfSatisfying(AiProviderException.class, e -> assertThat(e.type()).isEqualTo(AiFailureType.EMPTY_RESPONSE));
    }

    @Test
    void messagesNeverCarryKeys() {
        AiProviderException e = failure(HttpStatus.BAD_REQUEST,
                "{\"error\":{\"message\":\"bad key AIzaSyDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY1234\",\"status\":\"INVALID_ARGUMENT\"}}");
        assertThat(e.getMessage()).doesNotContain("AIza").contains("[redacted]");
        // Newer Google keys look different; they're redacted by shape too.
        AiProviderException newer = failure(HttpStatus.BAD_REQUEST,
                "{\"error\":{\"message\":\"bad key AQ.Ab8RN6DUMMYDUMMYDUMMYDUMMYDUMMY_x-1\",\"status\":\"INVALID_ARGUMENT\"}}");
        assertThat(newer.getMessage()).doesNotContain("AQ.Ab8").contains("[redacted]");
    }

    @Test
    void theConfiguredKeyIsRedactedWhateverItsFormat() {
        assertThat(ProviderHttp.sanitize("echo: some-future-key-format-123 rejected", "some-future-key-format-123"))
                .isEqualTo("echo: [redacted] rejected");
    }
}
