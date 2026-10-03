package com.engineeringlens.github;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The subset of GitHub's repository JSON we read. Never returned from our API; see {@link RepositorySummary}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubRepo(
        String name,
        String description,
        String language,
        @JsonProperty("stargazers_count") int stars,
        boolean fork,
        @JsonProperty("private") boolean privateRepo,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("pushed_at") Instant pushedAt) {
}
