package com.engineeringlens.github;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.engineeringlens.common.ApiException;

@RestController
@RequestMapping("/api/github")
public class GitHubController {

    private static final Logger log = LoggerFactory.getLogger(GitHubController.class);

    private final GitHubClient github;
    private final GitHubConnectionService connections;
    private final GitHubAppSettings settings;

    public GitHubController(GitHubClient github, GitHubConnectionService connections, GitHubAppSettings settings) {
        this.github = github;
        this.connections = connections;
        this.settings = settings;
    }

    /** Previews the public projects behind a pasted profile link (forks excluded). */
    @GetMapping("/projects")
    GitHubProjectsResponse projects(@RequestParam String profileUrl) {
        String username = GitHubProfileUrl.parseUsername(profileUrl);
        List<RepositorySummary> own = github.listPublicRepos(username).stream()
                .filter(r -> !r.fork())
                .map(RepositorySummary::from)
                .toList();
        return new GitHubProjectsResponse(username, own);
    }

    @GetMapping("/connection")
    GitHubConnectionStatus connection(@AuthenticationPrincipal Jwt jwt) {
        return connections.status(userId(jwt));
    }

    @PostMapping("/connection")
    Map<String, String> connect(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("url", connections.startConnect(userId(jwt)));
    }

    @GetMapping("/connection/repositories")
    List<RepositorySummary> repositories(@AuthenticationPrincipal Jwt jwt) {
        return connections.repositories(userId(jwt)).stream().map(RepositorySummary::from).toList();
    }

    @DeleteMapping("/connection")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disconnect(@AuthenticationPrincipal Jwt jwt) {
        connections.disconnect(userId(jwt));
    }

    /**
     * GitHub sends the user's browser here after they choose repositories. It is a
     * page navigation, not an API call, so it always redirects back to the app.
     */
    @GetMapping("/callback")
    ResponseEntity<Void> callback(@RequestParam(required = false) String state,
            @RequestParam(required = false) String code,
            @RequestParam(name = "installation_id", required = false) Long installationId) {
        String outcome;
        try {
            connections.completeConnect(state, code, installationId);
            outcome = "connected";
        } catch (ApiException e) {
            log.info("GitHub connect failed: {}", e.getCode());
            outcome = e.getCode();
        }
        URI back = UriComponentsBuilder.fromUriString(settings.frontendUrl() + "/onboarding")
                .queryParam("github", outcome).build().toUri();
        return ResponseEntity.status(HttpStatus.FOUND).location(back).build();
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
