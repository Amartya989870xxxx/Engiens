package com.engineeringlens.analysis;

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

/** One preparation attempt, with the versions and limits that make its output reproducible. */
@Entity
@Table(name = "analysis_runs")
public class AnalysisRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AnalysisRunStatus status;

    @Column(name = "commit_sha")
    private String commitSha;

    @Column(name = "profile_schema_version", nullable = false)
    private int profileSchemaVersion;

    @Column(name = "rules_version", nullable = false)
    private int rulesVersion;

    @Column(name = "context_schema_version", nullable = false)
    private int contextSchemaVersion;

    @Column(name = "config_fingerprint", nullable = false)
    private String configFingerprint;

    @Column(name = "files_fetched")
    private Integer filesFetched;

    @Column(name = "bytes_fetched")
    private Long bytesFetched;

    @Column(name = "signal_count")
    private Integer signalCount;

    @Column(name = "context_file_count")
    private Integer contextFileCount;

    @Column(name = "context_bytes")
    private Long contextBytes;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected AnalysisRun() {
    }

    AnalysisRun(UUID repositoryId, UUID userId, int profileSchemaVersion, int rulesVersion, int contextSchemaVersion,
            String configFingerprint) {
        this.repositoryId = repositoryId;
        this.userId = userId;
        this.profileSchemaVersion = profileSchemaVersion;
        this.rulesVersion = rulesVersion;
        this.contextSchemaVersion = contextSchemaVersion;
        this.configFingerprint = configFingerprint;
        this.status = AnalysisRunStatus.QUEUED;
        this.createdAt = Instant.now();
    }

    void start() {
        status = AnalysisRunStatus.RUNNING;
        startedAt = Instant.now();
    }

    void complete(String commitSha, int filesFetched, long bytesFetched, int signalCount, int contextFileCount, long contextBytes) {
        this.commitSha = commitSha;
        this.filesFetched = filesFetched;
        this.bytesFetched = bytesFetched;
        this.signalCount = signalCount;
        this.contextFileCount = contextFileCount;
        this.contextBytes = contextBytes;
        finish(AnalysisRunStatus.COMPLETED);
    }

    void fail(String reason) {
        failureReason = reason == null || reason.length() <= 300 ? reason : reason.substring(0, 300);
        finish(AnalysisRunStatus.FAILED);
    }

    private void finish(AnalysisRunStatus finalStatus) {
        status = finalStatus;
        completedAt = Instant.now();
        durationMs = startedAt == null ? null : completedAt.toEpochMilli() - startedAt.toEpochMilli();
    }

    public UUID getId() { return id; }
    public UUID getRepositoryId() { return repositoryId; }
    public UUID getUserId() { return userId; }
    public AnalysisRunStatus getStatus() { return status; }
    public String getCommitSha() { return commitSha; }
    public int getProfileSchemaVersion() { return profileSchemaVersion; }
    public int getRulesVersion() { return rulesVersion; }
    public int getContextSchemaVersion() { return contextSchemaVersion; }
    public String getConfigFingerprint() { return configFingerprint; }
    public Integer getFilesFetched() { return filesFetched; }
    public Long getBytesFetched() { return bytesFetched; }
    public Integer getSignalCount() { return signalCount; }
    public Integer getContextFileCount() { return contextFileCount; }
    public Long getContextBytes() { return contextBytes; }
    public Long getDurationMs() { return durationMs; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
