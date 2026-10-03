package com.engineeringlens.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.engineeringlens.common.ApiException;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;

class GitHubAppClientTest {

    private static final String API = "https://api.github.com";

    private KeyPair keys;
    private MockRestServiceServer server;
    private GitHubAppClient client;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keys = generator.generateKeyPair();
        GitHubAppSettings settings = new GitHubAppSettings("42", "Iv1.client", "secret", "lens",
                PemKeysTest.pem("PRIVATE KEY", keys.getPrivate().getEncoded()), "", "http://localhost:5173");
        RestClient.Builder builder = RestClient.builder().baseUrl(API);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GitHubAppClient(builder, settings);
    }

    @Test
    void appJwtIsSignedWithTheAppKey() throws Exception {
        SignedJWT jwt = SignedJWT.parse(client.appJwt());
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) keys.getPublic()))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("42");
    }

    @Test
    void exchangesCodeForUserToken() {
        server.expect(requestTo(GitHubAppClient.OAUTH_TOKEN_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"client_id\":\"Iv1.client\",\"code\":\"abc\"}"))
                .andRespond(withSuccess("{\"access_token\":\"ghu_1\",\"token_type\":\"bearer\"}", MediaType.APPLICATION_JSON));
        assertThat(client.exchangeCode("abc")).isEqualTo("ghu_1");
    }

    @Test
    void rejectedCodeIsAnAuthFailure() {
        // GitHub signals a bad code with HTTP 200 and an error field.
        server.expect(requestTo(GitHubAppClient.OAUTH_TOKEN_URL))
                .andRespond(withSuccess("{\"error\":\"bad_verification_code\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.exchangeCode("old"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("GITHUB_AUTH_FAILED"));
    }

    @Test
    void keepsOnlyInstallationsOfThisApp() {
        server.expect(requestTo(API + "/user/installations?per_page=100"))
                .andExpect(header("Authorization", "Bearer ghu_1"))
                .andRespond(withSuccess("""
                        {"total_count":2,"installations":[
                          {"id":7,"app_id":42,"account":{"login":"octo","type":"User"}},
                          {"id":8,"app_id":99,"account":{"login":"octo","type":"User"}}]}""", MediaType.APPLICATION_JSON));
        assertThat(client.userInstallations("ghu_1")).extracting(GitHubAppClient.Installation::id).containsExactly(7L);
    }

    @Test
    void readsSharedRepositoriesIncludingPrivateOnes() {
        server.expect(requestTo(API + "/app/installations/7/access_tokens")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"token\":\"ghs_1\",\"expires_at\":\"2026-10-03T12:00:00Z\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(API + "/installation/repositories?per_page=100"))
                .andExpect(header("Authorization", "Bearer ghs_1"))
                .andRespond(withSuccess("""
                        {"total_count":1,"repositories":[{"name":"secret-app","private":true,"fork":false,
                          "language":"Go","stargazers_count":0,"html_url":"https://github.com/octo/secret-app"}]}""",
                        MediaType.APPLICATION_JSON));
        assertThat(client.installationRepositories(7)).singleElement()
                .satisfies(r -> assertThat(r.privateRepo()).isTrue());
    }

    @Test
    void uninstalledAppMeansNotConnected() {
        server.expect(requestTo(API + "/app/installations/7/access_tokens")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> client.installationRepositories(7))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("GITHUB_NOT_CONNECTED"));
    }
}
