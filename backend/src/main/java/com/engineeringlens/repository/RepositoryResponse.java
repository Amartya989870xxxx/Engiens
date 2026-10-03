package com.engineeringlens.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Our API's view of an imported repository. GitHub's own JSON never reaches the frontend. */
public record RepositoryResponse(
        UUID id,
        String owner,
        String name,
        String url,
        String description,
        String defaultBranch,
        /** The imported commit; null for imports made before commits were recorded. */
        String commitSha,
        String primaryLanguage,
        RepositoryVisibility visibility,
        int stars,
        int forks,
        int fileCount,
        int relevantFileCount,
        int ignoredFileCount,
        RepositoryStatus status,
        String failureReason,
        Instant updatedAt,
        /** Relevant files per language, most common first. */
        List<Count> languages,
        /** Ignored files per reason, most common first. */
        List<Count> ignoredReasons) {

    public record Count(String label, long count) {
    }
}
