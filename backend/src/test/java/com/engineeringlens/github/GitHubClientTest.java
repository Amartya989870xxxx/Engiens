package com.engineeringlens.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.engineeringlens.common.ApiException;

class GitHubClientTest {

    private static final String URL = "https://api.github.com/users/octo/repos?type=owner&sort=pushed&per_page=100";

    private MockRestServiceServer server;
    private GitHubClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GitHubClient(builder);
    }

    @Test
    void mapsRepositories() {
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                [{"name":"lens","description":"Review tool","language":"Java","stargazers_count":3,
                  "fork":false,"private":false,"html_url":"https://github.com/octo/lens","pushed_at":"2026-09-01T10:00:00Z",
                  "owner":{"login":"octo"}}]""", MediaType.APPLICATION_JSON));

        var repos = client.listPublicRepos("octo");

        assertThat(repos).singleElement().satisfies(r -> {
            assertThat(r.name()).isEqualTo("lens");
            assertThat(r.stars()).isEqualTo(3);
            assertThat(r.htmlUrl()).isEqualTo("https://github.com/octo/lens");
        });
    }

    @Test
    void unknownUserBecomesNotFound() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> client.listPublicRepos("octo"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("GITHUB_USER_NOT_FOUND"));
    }

    @Test
    void rateLimitIsReportedSeparately() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> client.listPublicRepos("octo"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("GITHUB_RATE_LIMITED"));
    }

    @Test
    void serverErrorBecomesUnavailable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertThatThrownBy(() -> client.listPublicRepos("octo"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("GITHUB_UNAVAILABLE"));
    }
}
