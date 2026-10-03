package com.engineeringlens.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The subset of GitHub's single-repository JSON we read. Internal to the github module's callers. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubRepoDetails(
        String name,
        Owner owner,
        String description,
        @JsonProperty("default_branch") String defaultBranch,
        String language,
        @JsonProperty("private") boolean privateRepo,
        @JsonProperty("stargazers_count") int stars,
        @JsonProperty("forks_count") int forks,
        @JsonProperty("html_url") String htmlUrl) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Owner(String login) {
    }
}
