package com.engineeringlens.github;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * GitHub's recursive file tree. {@code truncated} means GitHub stopped listing because the
 * repository is too big (over 100,000 entries or 7 MB of tree data).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubTree(boolean truncated, List<Entry> tree) {

    /** type is "blob" (file), "tree" (directory) or "commit" (git submodule). size is only set for blobs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String path, String type, Long size) {
    }
}
