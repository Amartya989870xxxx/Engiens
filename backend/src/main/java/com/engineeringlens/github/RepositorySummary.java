package com.engineeringlens.github;

import java.time.Instant;

/**
 * What our API tells the frontend about a repository. Kept separate from
 * {@link GitHubRepo}, which mirrors GitHub's snake_case JSON, so GitHub's
 * format never leaks into our own API contract.
 */
public record RepositorySummary(
        String name,
        String description,
        String language,
        int stars,
        boolean privateRepo,
        String htmlUrl,
        Instant pushedAt) {

    static RepositorySummary from(GitHubRepo repo) {
        return new RepositorySummary(repo.name(), repo.description(), repo.language(), repo.stars(),
                repo.privateRepo(), repo.htmlUrl(), repo.pushedAt());
    }
}
