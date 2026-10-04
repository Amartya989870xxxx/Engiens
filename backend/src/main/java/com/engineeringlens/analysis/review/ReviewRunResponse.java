package com.engineeringlens.analysis.review;

import java.time.Instant;
import java.util.UUID;

import com.engineeringlens.analysis.review.model.ReviewDocument;

/**
 * A review run as the user sees it. Internal routing details (why a fallback happened, provider error
 * text) are logged and kept in model health, not exposed here.
 *
 * @param review the validated review; only on single-review responses of a completed run
 */
public record ReviewRunResponse(UUID id, UUID repositoryId, String repositoryName, UUID analysisRunId, ReviewRunStatus status,
        String commitSha, String provider, String model, boolean fallbackUsed, String errorCode, String errorMessage,
        Instant createdAt, Instant completedAt, Long durationMs, ReviewDocument review) {
}
