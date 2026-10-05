package com.engineeringlens.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.engineeringlens.analysis.context.ContextBuilder;
import com.engineeringlens.analysis.source.RunFileCache;
import com.engineeringlens.analysis.source.SourceSnapshot;
import com.engineeringlens.common.ApiException;

/** Public files come from raw.githubusercontent.com; when that host is down the same commit is read through the API. */
class GitHubRawFileFallbackTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String RAW = "https://raw.githubusercontent.com/octocat/Hello-World/" + SHA + "/";
    private static final String API = "https://api.github.com/repos/octocat/Hello-World/contents/";
    private static final String SOURCE = "def place(order):\n    return order\n";

    private MockRestServiceServer server;
    private GitHubClient client;

    @BeforeEach
    void setUp() {
        // The same client the server builds: the API base plus the server's own GITHUB_TOKEN as a default header.
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer server-token");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GitHubClient(builder);
    }

    private SourceSnapshot pinned() {
        return new SourceSnapshot() {
            @Override
            public String commitSha() {
                return SHA;
            }

            @Override
            public boolean pinnedAtImport() {
                return true;
            }

            @Override
            public byte[] read(String path) {
                return client.getRawFile("octocat", "Hello-World", SHA, path, null);
            }
        };
    }

    @Test
    void aRawHostTimeoutFallsBackToTheAuthenticatedApiAtTheSameCommitAndTheFileStillVerifies() {
        String manifestHash = ContextBuilder.sha256(SOURCE); // what preparation recorded
        server.expect(requestTo(RAW + "app/orders.py")).andRespond(withException(new SocketTimeoutException("connect timed out")));
        server.expect(requestTo(API + "app/orders.py?ref=" + SHA))
                .andExpect(header("Authorization", "Bearer server-token"))
                .andExpect(header("Accept", "application/vnd.github.raw"))
                .andRespond(withSuccess(SOURCE, MediaType.TEXT_PLAIN));
        // The raw host is now skipped for a while: the next file goes straight to the API, with no second timeout.
        server.expect(requestTo(API + "app/util.py?ref=" + SHA)).andRespond(withSuccess("tampered\n", MediaType.TEXT_PLAIN));

        RunFileCache cache = new RunFileCache(pinned());
        RunFileCache.Fetched orders = cache.fetch("app/orders.py");
        assertThat(orders.ok()).isTrue();
        assertThat(ContextBuilder.sha256(orders.text())).isEqualTo(manifestHash);

        // The fallback is not trusted blindly: bytes that differ from the manifest still fail verification.
        RunFileCache.Fetched util = cache.fetch("app/util.py");
        assertThat(ContextBuilder.sha256(util.text())).isNotEqualTo(ContextBuilder.sha256("def helper():\n    pass\n"));
        server.verify();
    }

    @Test
    void aRawHostServerErrorAlsoFallsBack() {
        server.expect(requestTo(RAW + "main.py")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(API + "main.py?ref=" + SHA)).andRespond(withSuccess(SOURCE, MediaType.TEXT_PLAIN));

        assertThat(new String(client.getRawFile("octocat", "Hello-World", SHA, "main.py", null))).isEqualTo(SOURCE);
        server.verify();
    }

    /** Seen on a real repository: an empty app/__init__.py made preparing the whole repository fail. */
    @Test
    void anEmptyFileIsReadAsEmptyNotAsAnOutage() {
        server.expect(requestTo(RAW + "app/__init__.py")).andRespond(withSuccess("", MediaType.TEXT_PLAIN));
        server.expect(requestTo(API + "pkg/__init__.py?ref=" + SHA)).andRespond(withSuccess("", MediaType.TEXT_PLAIN));

        assertThat(client.getRawFile("octocat", "Hello-World", SHA, "app/__init__.py", null)).isEmpty();
        assertThat(client.getRawFile("octocat", "Hello-World", SHA, "pkg/__init__.py", "ghs_installation")).isEmpty();
        server.verify();
    }

    @Test
    void whenBothPathsFailTheUsualGitHubErrorIsReturned() {
        server.expect(requestTo(RAW + "main.py")).andRespond(withException(new ConnectException("Connection refused")));
        server.expect(requestTo(API + "main.py?ref=" + SHA)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.getRawFile("octocat", "Hello-World", SHA, "main.py", null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("GITHUB_TIMEOUT");
                    assertThat(e.getMessage()).doesNotContain("server-token");
                });
        server.verify();
    }

    @Test
    void whenTheApiFallbackIsUnavailableTooThatIsReported() {
        server.expect(requestTo(RAW + "main.py")).andRespond(withException(new SocketTimeoutException("connect timed out")));
        server.expect(requestTo(API + "main.py?ref=" + SHA)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.getRawFile("octocat", "Hello-World", SHA, "main.py", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("GITHUB_UNAVAILABLE"));
        server.verify();
    }

    @Test
    void answersFromTheRawHostAreNotRetriedThroughTheApi() {
        server.expect(requestTo(RAW + "gone.py")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(RAW + "limited.py")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(RAW + "bad.py")).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.getRawFile("octocat", "Hello-World", SHA, "gone.py", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("SOURCE_FILE_NOT_FOUND"));
        assertThatThrownBy(() -> client.getRawFile("octocat", "Hello-World", SHA, "limited.py", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("GITHUB_RATE_LIMITED"));
        assertThatThrownBy(() -> client.getRawFile("octocat", "Hello-World", SHA, "bad.py", null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("GITHUB_UNAVAILABLE"));
        server.verify(); // no contents-API request was made
    }

    @Test
    void privateFilesStillUseOnlyTheInstallationToken() {
        server.expect(requestTo(API + "main.py?ref=" + SHA))
                .andExpect(header("Authorization", "Bearer ghs_installation"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> client.getRawFile("octocat", "Hello-World", SHA, "main.py", "ghs_installation"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("GITHUB_TIMEOUT"));
        server.verify(); // one request, never the raw host
    }
}
