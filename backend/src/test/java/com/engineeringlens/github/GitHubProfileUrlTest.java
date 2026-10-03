package com.engineeringlens.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.engineeringlens.common.ApiException;

class GitHubProfileUrlTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://github.com/octo-cat", "http://github.com/octo-cat/", "github.com/octo-cat",
            "https://www.github.com/octo-cat?tab=repositories", "  https://github.com/octo-cat  " })
    void acceptsProfileLinks(String url) {
        assertThat(GitHubProfileUrl.parseUsername(url)).isEqualTo("octo-cat");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "octo-cat", "https://gitlab.com/octo-cat", "https://github.com/", "https://github.com/-bad",
            "https://github.com/bad-", "https://github.com/a--b", "https://evilgithub.com/octo-cat",
            "https://github.com.evil.io/octo-cat" })
    void rejectsNonProfileLinks(String url) {
        assertThatThrownBy(() -> GitHubProfileUrl.parseUsername(url))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("GitHub profile link");
    }

    @Test
    void explainsWhenARepositoryLinkIsPasted() {
        assertThatThrownBy(() -> GitHubProfileUrl.parseUsername("https://github.com/octo-cat/hello-world"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("repository link");
    }
}
