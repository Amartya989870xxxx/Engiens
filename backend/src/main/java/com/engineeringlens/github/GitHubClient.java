package com.engineeringlens.github;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.engineeringlens.common.ApiException;

/**
 * Thin wrapper over the GitHub REST API. Reads public data by default; repository calls can
 * also take a short-lived GitHub App installation token to read private repositories.
 */
@Component
public class GitHubClient {

    private static final int MAX_REPOS = 100;

    static final String RAW_URL = "https://raw.githubusercontent.com";

    private final RestClient http;
    private final String rawUrl;

    @Autowired
    public GitHubClient(@Value("${app.github.api-url:https://api.github.com}") String apiUrl,
            @Value("${app.github.token:}") String token,
            @Value("${app.github.raw-url:" + RAW_URL + "}") String rawUrl) {
        this(configure(apiUrl, token), rawUrl);
    }

    GitHubClient(RestClient.Builder builder) {
        this(builder, RAW_URL);
    }

    GitHubClient(RestClient.Builder builder, String rawUrl) {
        this.http = builder.build();
        this.rawUrl = rawUrl;
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

    /** Every file and folder at a commit (or branch), in one call (no file contents). */
    public GitHubTree getTree(String owner, String name, String ref, String accessToken) {
        return call(() -> withToken(http.get().uri("/repos/{owner}/{name}/git/trees/{ref}?recursive=1", owner, name, ref), accessToken)
                .retrieve()
                // GitHub answers 409 Conflict for a repository with no commits yet.
                .onStatus(s -> s.value() == 409, (req, res) -> {
                    throw repositoryEmpty();
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

    /** The commit a branch currently points to, so later reads can be pinned to that exact snapshot. */
    public String getCommitSha(String owner, String name, String ref, String accessToken) {
        String sha = call(() -> withToken(http.get().uri("/repos/{owner}/{name}/commits/{ref}", owner, name, ref), accessToken)
                .accept(MediaType.parseMediaType("application/vnd.github.sha"))
                .retrieve()
                .onStatus(s -> s.value() == 409, (req, res) -> {
                    throw repositoryEmpty();
                })
                .onStatus(s -> s.value() == 404 || s.value() == 422, (req, res) -> {
                    throw repositoryNotFound();
                })
                .onStatus(s -> s.value() == 403 || s.value() == 429, (req, res) -> {
                    throw GitHubHttp.rateLimited();
                })
                .onStatus(s -> s.isError(), (req, res) -> {
                    throw GitHubHttp.unavailable();
                })
                .body(String.class)).trim();
        if (!sha.matches("[0-9a-f]{40}")) {
            throw GitHubHttp.unavailable();
        }
        return sha;
    }

    /**
     * Raw bytes of one file at a pinned commit. Public repositories are read from
     * raw.githubusercontent.com, which doesn't count against the REST API's hourly limit;
     * private ones through the contents API with the installation token.
     */
    public byte[] getRawFile(String owner, String name, String commitSha, String path, String accessToken) {
        String[] segments = path.split("/");
        RestClient.RequestHeadersSpec<?> request = accessToken == null
                ? http.get().uri(UriComponentsBuilder.fromUriString(rawUrl)
                        .pathSegment(owner, name, commitSha).pathSegment(segments).build().encode().toUri())
                        .accept(MediaType.ALL)
                : withToken(http.get().uri(b -> b.pathSegment("repos", owner, name, "contents").pathSegment(segments)
                        .queryParam("ref", commitSha).build()), accessToken)
                        .accept(MediaType.parseMediaType("application/vnd.github.raw"));
        return call(() -> request.retrieve()
                .onStatus(s -> s.value() == 404, (req, res) -> {
                    throw new ApiException(HttpStatus.NOT_FOUND, "SOURCE_FILE_NOT_FOUND", "File not found at the imported commit");
                })
                .onStatus(s -> s.value() == 403 || s.value() == 429, (req, res) -> {
                    throw GitHubHttp.rateLimited();
                })
                .onStatus(s -> s.isError(), (req, res) -> {
                    throw GitHubHttp.unavailable();
                })
                .body(byte[].class));
    }

    static ApiException repositoryEmpty() {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "REPOSITORY_EMPTY",
                "This repository is empty. Push some code to it, then import it again.");
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
