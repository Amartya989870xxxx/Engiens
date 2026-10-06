package com.engineeringlens.github;

import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.engineeringlens.common.ApiException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Calls GitHub as the Engiens GitHub App: verifies which installations
 * a user owns and reads the repositories they chose to share (read-only).
 */
@Component
public class GitHubAppClient {

    static final String OAUTH_TOKEN_URL = "https://github.com/login/oauth/access_token";

    private final RestClient http;
    private final GitHubAppSettings settings;
    private final RSAPrivateKey privateKey;

    @Autowired
    public GitHubAppClient(GitHubAppSettings settings) {
        this(GitHubHttp.builder(GitHubHttp.API_URL), settings);
    }

    GitHubAppClient(RestClient.Builder builder, GitHubAppSettings settings) {
        this.http = builder.build();
        this.settings = settings;
        this.privateKey = settings.configured() ? PemKeys.readRsaPrivateKey(settings.privateKeyPem()) : null;
    }

    /** Exchanges the one-time OAuth code from the callback for a short-lived user token. */
    public String exchangeCode(String code) {
        OAuthToken token = call(() -> http.post()
                .uri(OAUTH_TOKEN_URL)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("client_id", settings.clientId(), "client_secret", settings.clientSecret(), "code", code))
                .retrieve()
                .onStatus(s -> s.isError(), (req, res) -> {
                    throw GitHubHttp.unavailable();
                })
                .body(OAuthToken.class));
        // GitHub reports bad or expired codes with HTTP 200 and an "error" field.
        if (token == null || token.accessToken() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GITHUB_AUTH_FAILED",
                    "GitHub sign-in did not complete. Please try connecting again.");
        }
        return token.accessToken();
    }

    public String userLogin(String userToken) {
        GitHubUser user = call(() -> asUser(http.get().uri("/user"), userToken).body(GitHubUser.class));
        return user.login();
    }

    /** Installations of this App that the signed-in GitHub user can access. */
    public List<Installation> userInstallations(String userToken) {
        InstallationPage page = call(() -> asUser(http.get().uri("/user/installations?per_page=100"), userToken)
                .body(InstallationPage.class));
        long appId = settings.appIdAsLong();
        return page.installations().stream().filter(i -> i.appId() == appId).toList();
    }

    /** Every repository the user shared with the installation, public and private. */
    public List<GitHubRepo> installationRepositories(long installationId) {
        String token = installationToken(installationId);
        RepositoryPage page = call(() -> http.get()
                .uri("/installation/repositories?per_page=100")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .onStatus(s -> s.isError(), (req, res) -> {
                    throw GitHubHttp.unavailable();
                })
                .body(RepositoryPage.class));
        return page.repositories();
    }

    /** Short-lived (1 hour) token scoped to the repositories in one installation. */
    String installationToken(long installationId) {
        InstallationToken token = call(() -> http.post()
                .uri("/app/installations/{id}/access_tokens", installationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + appJwt())
                .retrieve()
                .onStatus(s -> s.value() == 404, (req, res) -> {
                    throw installationGone();
                })
                .onStatus(s -> s.isError(), (req, res) -> {
                    throw GitHubHttp.unavailable();
                })
                .body(InstallationToken.class));
        return token.token();
    }

    /** JWT proving we are the App; GitHub accepts at most 10 minutes of validity. */
    String appJwt() {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(settings.appId())
                .issueTime(Date.from(now.minusSeconds(60))) // tolerate clock drift
                .expirationTime(Date.from(now.plusSeconds(540)))
                .build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner(privateKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign GitHub App JWT", e);
        }
    }

    static ApiException installationGone() {
        return new ApiException(HttpStatus.NOT_FOUND, "GITHUB_NOT_CONNECTED",
                "GitHub access was removed. Connect GitHub again to share private repositories.");
    }

    private static RestClient.ResponseSpec asUser(RestClient.RequestHeadersSpec<?> request, String userToken) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                .retrieve()
                .onStatus(s -> s.value() == 401, (req, res) -> {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "GITHUB_AUTH_FAILED",
                            "GitHub sign-in did not complete. Please try connecting again.");
                })
                .onStatus(s -> s.value() == 403 || s.value() == 429, (req, res) -> {
                    throw GitHubHttp.rateLimited();
                })
                .onStatus(s -> s.isError(), (req, res) -> {
                    throw GitHubHttp.unavailable();
                });
    }

    private static <T> T call(java.util.function.Supplier<T> request) {
        try {
            T result = request.get();
            if (result == null) {
                throw GitHubHttp.unavailable();
            }
            return result;
        } catch (ResourceAccessException e) {
            throw GitHubHttp.networkFailure(e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OAuthToken(@JsonProperty("access_token") String accessToken, String error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GitHubUser(String login) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Installation(long id, @JsonProperty("app_id") long appId, Account account) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Account(String login, String type) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InstallationPage(List<Installation> installations) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RepositoryPage(List<GitHubRepo> repositories) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InstallationToken(String token) {
    }
}
