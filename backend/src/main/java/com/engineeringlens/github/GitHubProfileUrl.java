package com.engineeringlens.github;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import com.engineeringlens.common.ApiException;

/** Extracts a GitHub username from a pasted profile link. */
public final class GitHubProfileUrl {

    // GitHub usernames: 1-39 chars, alphanumeric or single hyphens, not starting/ending with a hyphen.
    private static final Pattern PROFILE = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?github\\.com/([A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38})/?(?:[?#].*)?$");
    private static final Pattern REPOSITORY = Pattern.compile("^(?:https?://)?(?:www\\.)?github\\.com/[^/]+/[^/?#]+.*$");

    private GitHubProfileUrl() {
    }

    public static String parseUsername(String url) {
        String trimmed = url == null ? "" : url.trim();
        Matcher m = PROFILE.matcher(trimmed);
        if (m.matches()) {
            return m.group(1);
        }
        if (REPOSITORY.matcher(trimmed).matches()) {
            throw invalid("That looks like a repository link. Paste your profile link, e.g. https://github.com/your-name");
        }
        throw invalid("Enter a GitHub profile link, e.g. https://github.com/your-name");
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GITHUB_URL", message);
    }
}
