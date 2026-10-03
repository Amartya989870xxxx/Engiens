package com.engineeringlens.github;

import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.engineeringlens.common.ApiException;

/**
 * Reads a repository the signed-in user may access: public repositories directly, private ones
 * through the user's GitHub App installation. Installation tokens are created and used here only;
 * callers outside the github module never see a token.
 */
@Service
public class GitHubRepositoryReader {

    private final GitHubClient github;
    private final GitHubAppClient app;
    private final GitHubAppSettings settings;
    private final GitHubConnectionRepository connections;

    public GitHubRepositoryReader(GitHubClient github, GitHubAppClient app, GitHubAppSettings settings,
            GitHubConnectionRepository connections) {
        this.github = github;
        this.app = app;
        this.settings = settings;
        this.connections = connections;
    }

    /**
     * A repository as GitHub describes it, plus how we got access.
     * @param installationId the GitHub App installation that grants access, or null for public access
     */
    public record RemoteRepository(GitHubRepoDetails details, Long installationId) {

        public boolean viaInstallation() {
            return installationId != null;
        }
    }

    /**
     * Public first (no token, works for anyone). GitHub answers 404 for private repositories we can't
     * see, so on 404 we retry with the user's installation, if they connected one.
     */
    public RemoteRepository read(UUID userId, String owner, String name) {
        try {
            return new RemoteRepository(github.getRepository(owner, name, null), null);
        } catch (ApiException e) {
            if (!"REPOSITORY_NOT_FOUND".equals(e.getCode())) {
                throw e;
            }
        }

        Optional<GitHubConnection> connection = settings.configured() ? connections.findById(userId) : Optional.empty();
        if (connection.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND",
                    "We couldn't find that repository. If it's private, connect GitHub on your profile and share it with Engiens.");
        }
        long installationId = connection.get().getInstallationId();
        try {
            return new RemoteRepository(github.getRepository(owner, name, app.installationToken(installationId)), installationId);
        } catch (ApiException e) {
            if ("REPOSITORY_NOT_FOUND".equals(e.getCode())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "REPOSITORY_NO_ACCESS",
                        "You don't have access to this repository through your connected GitHub account.");
            }
            throw e;
        }
    }

    /** The full file tree of the default branch, using the same access path as {@link #read}. */
    public GitHubTree readTree(RemoteRepository repository) {
        GitHubRepoDetails d = repository.details();
        // A fresh one-hour token per call: nothing long-lived is ever held or stored.
        String token = repository.viaInstallation() ? app.installationToken(repository.installationId()) : null;
        return github.getTree(d.owner().login(), d.name(), d.defaultBranch(), token);
    }
}
