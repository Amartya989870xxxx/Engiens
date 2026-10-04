package com.engineeringlens.analysis.review;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One review attempt: which prepared context, which versions, which model, and how it went. */
@Entity
@Table(name = "review_runs")
public class ReviewRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "analysis_run_id", nullable = false)
    private UUID analysisRunId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewRunStatus status;

    @Column(name = "review_schema_version", nullable = false)
    private int reviewSchemaVersion;

    @Column(name = "rubric_version", nullable = false)
    private int rubricVersion;

    @Column(name = "context_schema_version", nullable = false)
    private int contextSchemaVersion;

    @Column(name = "commit_sha")
    private String commitSha;

    private String provider;

    private String model;

    @Column(name = "fallback_used", nullable = false)
    private boolean fallbackUsed;

    @Column(name = "fallback_reason")
    private String fallbackReason;

    @Column(name = "attempt_count")
    private Integer attemptCount;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    protected ReviewRun() {
    }

    ReviewRun(UUID repositoryId, UUID analysisRunId, UUID userId, String commitSha, int reviewSchemaVersion, int rubricVersion,
            int contextSchemaVersion) {
        this.repositoryId = repositoryId;
        this.analysisRunId = analysisRunId;
        this.userId = userId;
        this.commitSha = commitSha;
        this.reviewSchemaVersion = reviewSchemaVersion;
        this.rubricVersion = rubricVersion;
        this.contextSchemaVersion = contextSchemaVersion;
        this.status = ReviewRunStatus.QUEUED;
        this.createdAt = Instant.now();
    }

    void start() {
        status = ReviewRunStatus.RUNNING;
        startedAt = Instant.now();
    }

    void complete(String provider, String model, boolean fallbackUsed, String fallbackReason, int attempts, Integer inputTokens,
            Integer outputTokens) {
        this.provider = provider;
        this.model = model;
        this.fallbackUsed = fallbackUsed;
        this.fallbackReason = fallbackReason;
        this.attemptCount = attempts;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        finish(ReviewRunStatus.COMPLETED);
    }

    void fail(String code, String userMessage) {
        errorCode = code;
        errorMessage = userMessage == null || userMessage.length() <= 300 ? userMessage : userMessage.substring(0, 300);
        finish(ReviewRunStatus.FAILED);
    }

    private void finish(ReviewRunStatus finalStatus) {
        status = finalStatus;
        completedAt = Instant.now();
        durationMs = startedAt == null ? null : completedAt.toEpochMilli() - startedAt.toEpochMilli();
    }

    public UUID getId() { return id; }
    public UUID getRepositoryId() { return repositoryId; }
    public UUID getAnalysisRunId() { return analysisRunId; }
    public UUID getUserId() { return userId; }
    public ReviewRunStatus getStatus() { return status; }
    public int getReviewSchemaVersion() { return reviewSchemaVersion; }
    public int getRubricVersion() { return rubricVersion; }
    public int getContextSchemaVersion() { return contextSchemaVersion; }
    public String getCommitSha() { return commitSha; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public boolean isFallbackUsed() { return fallbackUsed; }
    public String getFallbackReason() { return fallbackReason; }
    public Integer getAttemptCount() { return attemptCount; }
    public Integer getInputTokens() { return inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Long getDurationMs() { return durationMs; }
}
