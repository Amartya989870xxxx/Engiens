package com.engineeringlens.github;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.engineeringlens.common.ApiException;

/**
 * Thin wrapper over the GitHub REST API. Reads public data by default; repository calls can
 * also take a short-lived GitHub App installation token to read private repositories.
 */
@Component
public class GitHubClient {

    private static final int MAX_REPOS = 100;

    private final RestClient http;

    @Autowired
    public GitHubClient(@Value("${app.github.api-url:https://api.github.com}") String apiUrl,
            @Value("${app.github.token:}") String token) {
        this(configure(apiUrl, token));
    }

    GitHubClient(RestClient.Builder builder) {
        this.http = builder.build();
    }

    private static RestClient.Builder configure(String apiUrl, String token) {
        RestClient.Builder builder = GitHubHttp.builder(apiUrl);
        // Optional: a token raises the rate limit from 60 to 5000 requests/hour.
        if (!token.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return builder;
    }

    /** Public repositories the user owns, most recently pushed first. */
    public List<GitHubRepo> listPublicRepos(String username) {
        try {
            GitHubRepo[] repos = http.get()
                    .uri("/users/{username}/repos?type=owner&sort=pushed&per_page={max}", username, MAX_REPOS)
                    .retrieve()
                    .onStatus(s -> s.value() == 404, (req, res) -> {
                        throw new ApiException(HttpStatus.NOT_FOUND, "GITHUB_USER_NOT_FOUND",
                                "No GitHub user named '" + username + "' was found");
                    })
                    .onStatus(s -> s.value() == 403 || s.value() == 429, (req, res) -> {
                        throw GitHubHttp.rateLimited();
                    })
                    .onStatus(s -> s.isError(), (req, res) -> {
                        throw GitHubHttp.unavailable();
                    })
                    .body(new ParameterizedTypeReference<GitHubRepo[]>() {
                    });
            return repos == null ? List.of() : Arrays.asList(repos);
        } catch (ResourceAccessException e) {
            throw GitHubHttp.networkFailure(e);
        }
    }

    /**
     * One repository's metadata.
     * @param accessToken installation token for private repositories, or null for public access
     */
    public GitHubRepoDetails getRepository(String owner, String name, String accessToken) {
        return call(() -> withToken(http.get().uri("/repos/{owner}/{name}", owner, name), accessToken)
                .retrieve()
                .onStatus(s -> s.value() == 404, (req, res) -> {
                    throw repositoryNotFound();
                })
                .onStatus(s -> s.value() == 403 || s.value() == 429, (req, res) -> {
                    throw GitHubHttp.rateLimited();
                })
                .onStatus(s -> s.isError(), (req, res) -> {
                    throw GitHubHttp.unavailable();
                })
                .body(GitHubRepoDetails.class));
    }

    /** Every file and folder on a branch, in one call (no file contents). */
    public GitHubTree getTree(String owner, String name, String branch, String accessToken) {
        return call(() -> withToken(http.get().uri("/repos/{owner}/{name}/git/trees/{branch}?recursive=1", owner, name, branch), accessToken)
                .retrieve()
                // GitHub answers 409 Conflict for a repository with no commits yet.
                .onStatus(s -> s.value() == 409, (req, res) -> {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "REPOSITORY_EMPTY",
                            "This repository is empty. Push some code to it, then import it again.");
                })
                .onStatus(s -> s.value() == 404, (req, res) -> {
                    throw repositoryNotFound();
                })
                .onStatus(s -> s.value() == 403 || s.value() == 429, (req, res) -> {
                    throw GitHubHttp.rateLimited();
                })
                .onStatus(s -> s.isError(), (req, res) -> {
                    throw GitHubHttp.unavailable();
                })
                .body(GitHubTree.class));
    }

    static ApiException repositoryNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "We couldn't find that repository.");
    }

    private static RestClient.RequestHeadersSpec<?> withToken(RestClient.RequestHeadersSpec<?> request, String accessToken) {
        return accessToken == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
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
}
