package com.engineeringlens.analysis;

import java.time.Instant;
import java.util.UUID;

import com.engineeringlens.analysis.context.ContextManifest;
import com.engineeringlens.analysis.deterministic.DeterministicAnalysis;
import com.engineeringlens.analysis.profile.RepositoryProfile;

/** A preparation run and, when it completed, its profile, signals and context manifest. */
public record AnalysisRunResponse(
        UUID id,
        UUID repositoryId,
        AnalysisRunStatus status,
        String commitSha,
        int profileSchemaVersion,
        int rulesVersion,
        int contextSchemaVersion,
        String failureReason,
        Instant createdAt,
        Instant completedAt,
        Long durationMs,
        Stats stats,
        RepositoryProfile profile,
        DeterministicAnalysis analysis,
        ContextManifest manifest) {

    public record Stats(Integer filesFetched, Long bytesFetched, Integer signalCount, Integer contextFileCount, Long contextBytes) {
    }
}
