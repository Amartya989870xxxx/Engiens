package com.engineeringlens.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.engineeringlens.common.ApiException;

class GitHubRepoUrlTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://github.com/octocat/Hello-World", "github.com/octocat/Hello-World", "https://github.com/octocat/Hello-World/",
            "https://github.com/octocat/Hello-World.git", "https://github.com/octocat/Hello-World/tree/main/src",
            "https://www.github.com/octocat/Hello-World?tab=readme", "  https://github.com/octocat/Hello-World  " })
    void acceptsRepositoryLinks(String url) {
        assertThat(GitHubRepoUrl.parse(url)).isEqualTo(new GitHubRepoUrl.OwnerAndName("octocat", "Hello-World"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "octocat/Hello-World", "https://github.com/octocat", "https://gitlab.com/octocat/repo",
            "https://evilgithub.com/octocat/repo", "https://github.com.evil.io/octocat/repo", "https://github.com/-bad/repo",
            "https://github.com/octocat/..", "https://github.com/octocat/has space" })
    void rejectsAnythingElse(String url) {
        assertThatThrownBy(() -> GitHubRepoUrl.parse(url))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_REPOSITORY_URL"));
    }
}
