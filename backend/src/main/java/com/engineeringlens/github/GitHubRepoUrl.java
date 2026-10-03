package com.engineeringlens.github;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import com.engineeringlens.common.ApiException;

/** Extracts owner and name from a pasted repository link, e.g. https://github.com/octocat/Hello-World. */
public final class GitHubRepoUrl {

    // Owner: GitHub username rules. Name: letters, digits, '.', '-', '_' (max 100). Anything after the
    // name (/tree/main, /blob/..., ?tab=...) is ignored so links copied from any repo page still work.
    private static final Pattern REPO = Pattern.compile(
            "^(?:https?://)?(?:www\\.)?github\\.com/([A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38})/([A-Za-z0-9._-]{1,100}?)(?:\\.git)?(?:[/?#].*)?$");

    public record OwnerAndName(String owner, String name) {
    }

    private GitHubRepoUrl() {
    }

    public static OwnerAndName parse(String url) {
        Matcher m = REPO.matcher(url == null ? "" : url.trim());
        if (!m.matches() || m.group(2).equals(".") || m.group(2).equals("..")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REPOSITORY_URL",
                    "Enter a GitHub repository link, e.g. https://github.com/owner/repository");
        }
        return new OwnerAndName(m.group(1), m.group(2));
    }
}
