package com.engineeringlens.github;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.engineeringlens.common.ApiException;
import com.engineeringlens.github.GitHubAppClient.Installation;

/**
 * Links a user to their GitHub App installation so we can read the
 * repositories (including private ones) they explicitly chose to share.
 */
@Service
public class GitHubConnectionService {

    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GitHubAppSettings settings;
    private final GitHubAppClient github;
    private final GitHubConnectionRepository connections;
    private final GitHubConnectStateRepository states;

    public GitHubConnectionService(GitHubAppSettings settings, GitHubAppClient github,
            GitHubConnectionRepository connections, GitHubConnectStateRepository states) {
        this.settings = settings;
        this.github = github;
        this.connections = connections;
        this.states = states;
    }

    /** Returns the GitHub URL where the user picks which repositories to share. */
    @Transactional
    public String startConnect(UUID userId) {
        requireAvailable();
        states.deleteByUserId(userId); // only the latest attempt is valid
        String state = randomToken();
        states.save(new GitHubConnectState(state, userId, Instant.now().plus(STATE_TTL)));
        return "https://github.com/apps/" + settings.slug() + "/installations/new?state=" + state;
    }

    /**
     * Handles GitHub's redirect. Deliberately not one transaction: the GitHub
     * calls below should not hold a database connection open.
     */
    public void completeConnect(String state, String code, Long installationId) {
        requireAvailable();
        GitHubConnectState pending = state == null ? null : states.findById(state).orElse(null);
        if (pending == null || pending.isExpired(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GITHUB_STATE_INVALID",
                    "This GitHub link has expired. Please start connecting again.");
        }
        states.delete(pending); // single use, even if the rest fails
        if (code == null || code.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GITHUB_AUTH_FAILED",
                    "GitHub sign-in did not complete. Please try connecting again.");
        }

        String userToken = github.exchangeCode(code);
        String login = github.userLogin(userToken);
        Installation installation = chooseInstallation(github.userInstallations(userToken), installationId, login);

        GitHubConnection connection = connections.findById(pending.getUserId())
                .orElseGet(() -> new GitHubConnection(pending.getUserId()));
        connection.connect(installation.id(), login);
        connections.save(connection);
    }

    /**
     * The installation id in the redirect URL can be tampered with, so it only counts
     * if GitHub confirms the signed-in user can access it.
     */
    static Installation chooseInstallation(List<Installation> mine, Long requestedId, String login) {
        if (requestedId != null) {
            return mine.stream().filter(i -> i.id() == requestedId).findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "GITHUB_INSTALLATION_NOT_YOURS",
                            "That GitHub installation doesn't belong to your GitHub account."));
        }
        return mine.stream().filter(i -> i.account() != null && login.equalsIgnoreCase(i.account().login()))
                .findFirst()
                .or(() -> mine.stream().findFirst())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "GITHUB_NOT_INSTALLED",
                        "The Engiens GitHub App isn't installed on your GitHub account yet. Please try connecting again."));
    }

    @Transactional(readOnly = true)
    public GitHubConnectionStatus status(UUID userId) {
        if (!settings.configured()) {
            return new GitHubConnectionStatus(false, false, null, null);
        }
        return connections.findById(userId)
                .map(c -> new GitHubConnectionStatus(true, true, c.getGithubLogin(),
                        "https://github.com/settings/installations/" + c.getInstallationId()))
                .orElse(new GitHubConnectionStatus(true, false, null, null));
    }

    public List<GitHubRepo> repositories(UUID userId) {
        requireAvailable();
        GitHubConnection connection = connections.findById(userId).orElseThrow(GitHubAppClient::installationGone);
        try {
            return github.installationRepositories(connection.getInstallationId());
        } catch (ApiException e) {
            // The user uninstalled the App on GitHub; forget the stale link.
            if ("GITHUB_NOT_CONNECTED".equals(e.getCode())) {
                connections.deleteById(userId);
            }
            throw e;
        }
    }

    @Transactional
    public void disconnect(UUID userId) {
        connections.deleteById(userId);
    }

    private void requireAvailable() {
        if (!settings.configured()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GITHUB_APP_NOT_CONFIGURED",
                    "Private repository access is not set up on this server.");
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
