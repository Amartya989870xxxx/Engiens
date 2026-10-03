package com.engineeringlens.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.engineeringlens.common.ApiException;

class GitHubRepositoryReaderTest {

    private static final UUID USER = UUID.randomUUID();
    private static final GitHubRepoDetails PUBLIC = details(false);
    private static final GitHubRepoDetails PRIVATE = details(true);

    private final GitHubClient github = mock(GitHubClient.class);
    private final GitHubAppClient app = mock(GitHubAppClient.class);
    private final GitHubConnectionRepository connections = mock(GitHubConnectionRepository.class);
    private GitHubRepositoryReader reader;

    private static GitHubRepoDetails details(boolean privateRepo) {
        return new GitHubRepoDetails("lens", new GitHubRepoDetails.Owner("asha"), null, "main", "Java", privateRepo, 0, 0,
                "https://github.com/asha/lens");
    }

    @BeforeEach
    void setUp() {
        GitHubAppSettings settings = new GitHubAppSettings("42", "Iv1.x", "secret", "engiens", "key-present", "", "http://localhost:5173");
        reader = new GitHubRepositoryReader(github, app, settings, connections);
    }

    private void connectInstallation(long installationId) {
        GitHubConnection connection = new GitHubConnection(USER);
        connection.connect(installationId, "asha");
        when(connections.findById(USER)).thenReturn(Optional.of(connection));
    }

    @Test
    void publicRepositoryNeedsNoToken() {
        when(github.getRepository("asha", "lens", null)).thenReturn(PUBLIC);

        GitHubRepositoryReader.RemoteRepository repo = reader.read(USER, "asha", "lens");

        assertThat(repo.viaInstallation()).isFalse();
        verify(app, never()).installationToken(any(Long.class));
    }

    @Test
    void privateRepositoryUsesTheUsersInstallation() {
        when(github.getRepository("asha", "lens", null)).thenThrow(GitHubClient.repositoryNotFound());
        connectInstallation(7);
        when(app.installationToken(7)).thenReturn("ghs_1");
        when(github.getRepository("asha", "lens", "ghs_1")).thenReturn(PRIVATE);

        GitHubRepositoryReader.RemoteRepository repo = reader.read(USER, "asha", "lens");
        assertThat(repo.installationId()).isEqualTo(7L);
        assertThat(repo.details().privateRepo()).isTrue();

        // The tree is read with a freshly minted token for the same installation.
        when(app.installationToken(7)).thenReturn("ghs_2");
        reader.readTree(repo);
        verify(github).getTree("asha", "lens", "main", "ghs_2");
    }

    @Test
    void privateRepositoryWithoutAConnectionIsNotFound() {
        when(github.getRepository("asha", "lens", null)).thenThrow(GitHubClient.repositoryNotFound());
        when(connections.findById(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reader.read(USER, "asha", "lens"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("REPOSITORY_NOT_FOUND");
                    assertThat(e.getMessage()).contains("connect GitHub");
                });
    }

    @Test
    void repositoryOutsideTheInstallationIsAnAccessError() {
        when(github.getRepository("asha", "lens", null)).thenThrow(GitHubClient.repositoryNotFound());
        connectInstallation(7);
        when(app.installationToken(7)).thenReturn("ghs_1");
        when(github.getRepository("asha", "lens", "ghs_1")).thenThrow(GitHubClient.repositoryNotFound());

        assertThatThrownBy(() -> reader.read(USER, "asha", "lens"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("REPOSITORY_NO_ACCESS");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });
    }

    @Test
    void rateLimitIsNotMistakenForAPrivateRepository() {
        when(github.getRepository("asha", "lens", null)).thenThrow(GitHubHttp.rateLimited());

        assertThatThrownBy(() -> reader.read(USER, "asha", "lens"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("GITHUB_RATE_LIMITED"));
        verify(connections, never()).findById(any());
    }
}
