package com.engineeringlens.repository;

import java.time.Instant;
import java.util.UUID;

/** A compact row for lists such as the sidebar's recent repositories. */
public record RepositoryListItem(UUID id, String owner, String name, RepositoryStatus status, Instant updatedAt) {

    static RepositoryListItem from(ImportedRepo r) {
        return new RepositoryListItem(r.getId(), r.getGithubOwner(), r.getGithubRepoName(), r.getStatus(), r.getUpdatedAt());
    }
}
