package com.engineeringlens.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;

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

    private static final String REPO_URL = "https://api.github.com/repos/octocat/Hello-World";

    @Test
    void mapsRepositoryMetadata() {
        server.expect(requestTo(REPO_URL)).andRespond(withSuccess("""
                {"name":"Hello-World","owner":{"login":"octocat"},"description":"My first repo","default_branch":"main",
                 "language":"Java","private":false,"stargazers_count":12,"forks_count":3,
                 "html_url":"https://github.com/octocat/Hello-World","size":108}""", MediaType.APPLICATION_JSON));

        GitHubRepoDetails d = client.getRepository("octocat", "Hello-World", null);

        assertThat(d.owner().login()).isEqualTo("octocat");
        assertThat(d.defaultBranch()).isEqualTo("main");
        assertThat(d.stars()).isEqualTo(12);
        assertThat(d.forks()).isEqualTo(3);
        assertThat(d.privateRepo()).isFalse();
    }

    @Test
    void sendsInstallationTokenAndReadsTree() {
        server.expect(requestTo(REPO_URL + "/git/trees/main?recursive=1"))
                .andExpect(header("Authorization", "Bearer ghs_token"))
                .andRespond(withSuccess("""
                        {"sha":"abc","truncated":false,"tree":[
                          {"path":"src","type":"tree"},{"path":"src/App.java","type":"blob","size":2400}]}""",
                        MediaType.APPLICATION_JSON));

        GitHubTree tree = client.getTree("octocat", "Hello-World", "main", "ghs_token");

        assertThat(tree.truncated()).isFalse();
        assertThat(tree.tree()).extracting(GitHubTree.Entry::path).containsExactly("src", "src/App.java");
    }

    @Test
    void missingRepositoryIsNotFound() {
        server.expect(requestTo(REPO_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> client.getRepository("octocat", "Hello-World", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("REPOSITORY_NOT_FOUND"));
    }

    @Test
    void repositoryRateLimitIsReported() {
        server.expect(requestTo(REPO_URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> client.getRepository("octocat", "Hello-World", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("GITHUB_RATE_LIMITED"));
    }

    @Test
    void slowGitHubIsATimeout() {
        server.expect(requestTo(REPO_URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));
        assertThatThrownBy(() -> client.getRepository("octocat", "Hello-World", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("GITHUB_TIMEOUT"));
    }

    @Test
    void emptyRepositoryIsReported() {
        // GitHub answers 409 Conflict when a repository has no commits.
        server.expect(requestTo(REPO_URL + "/git/trees/main?recursive=1")).andRespond(withStatus(HttpStatus.CONFLICT));
        assertThatThrownBy(() -> client.getTree("octocat", "Hello-World", "main", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("REPOSITORY_EMPTY"));
    }

    @Test
    void resolvesABranchToItsCommit() {
        server.expect(requestTo(REPO_URL + "/commits/main"))
                .andExpect(header("Accept", "application/vnd.github.sha"))
                .andRespond(withSuccess("0123456789abcdef0123456789abcdef01234567\n", MediaType.TEXT_PLAIN));
        assertThat(client.getCommitSha("octocat", "Hello-World", "main", null)).isEqualTo("0123456789abcdef0123456789abcdef01234567");
    }

    @Test
    void publicFilesComeFromTheRawHostWithoutUsingTheApiLimit() {
        server.expect(requestTo("https://raw.githubusercontent.com/octocat/Hello-World/c0ffee/src/my%20app/main.py"))
                .andRespond(withSuccess("print('hi')", MediaType.TEXT_PLAIN));
        assertThat(new String(client.getRawFile("octocat", "Hello-World", "c0ffee", "src/my app/main.py", null)))
                .isEqualTo("print('hi')");
    }

    @Test
    void privateFilesUseTheContentsApiWithTheToken() {
        server.expect(requestTo(REPO_URL + "/contents/src/main.py?ref=c0ffee"))
                .andExpect(header("Authorization", "Bearer ghs_token"))
                .andExpect(header("Accept", "application/vnd.github.raw"))
                .andRespond(withSuccess("print('private')", MediaType.TEXT_PLAIN));
        assertThat(new String(client.getRawFile("octocat", "Hello-World", "c0ffee", "src/main.py", "ghs_token")))
                .isEqualTo("print('private')");
    }

    @Test
    void missingFileIsReportedAsSuch() {
        server.expect(requestTo("https://raw.githubusercontent.com/octocat/Hello-World/c0ffee/gone.py"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> client.getRawFile("octocat", "Hello-World", "c0ffee", "gone.py", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SOURCE_FILE_NOT_FOUND"));
    }
}
