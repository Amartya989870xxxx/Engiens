package com.engineeringlens.scenario;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One Scenario Lab: a stable snapshot (repository, commit, originating review, roles, seniority, size,
 * versions) plus its lifecycle. While open it is the user's workspace; afterwards the same row anchors the
 * permanent assessment. The snapshot fields never change after creation.
 */
@Entity
@Table(name = "scenario_labs")
public class ScenarioLab {

    /** Bumped when the generation prompts/pipeline change in a way that makes labs not comparable. */
    public static final int GENERATOR_VERSION = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    /** The stable review identity (reviews.review_run_id, the id in /reviews/:id); null for a direct lab. */
    @Column(name = "review_id")
    private UUID reviewId;

    @Column(name = "analysis_run_id", nullable = false)
    private UUID analysisRunId;

    @Column(name = "commit_sha", nullable = false)
    private String commitSha;

    @Convert(converter = RoleListConverter.class)
    @Column(nullable = false)
    private List<ScenarioRole> roles;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Seniority seniority;

    @Column(name = "scenario_count", nullable = false)
    private int scenarioCount;

    @Column(name = "scenario_schema_version", nullable = false)
    private int scenarioSchemaVersion;

    @Column(name = "generator_version", nullable = false)
    private int generatorVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScenarioLabStatus status;

    /** user_id while open, null otherwise; unique, so a user can have only one open lab. */
    @Column(name = "active_user_id", unique = true)
    private UUID activeUserId;

    @Column(name = "scenarios_ready", nullable = false)
    private int scenariosReady;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    /** Optimistic lock: two requests changing the same lab at once can't both win (e.g. completing twice). */
    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "generated_at")
    private Instant generatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected ScenarioLab() {
    }

    public ScenarioLab(UUID userId, UUID repositoryId, UUID reviewId, UUID analysisRunId, String commitSha, List<ScenarioRole> roles,
            Seniority seniority, int scenarioCount) {
        this.userId = userId;
        this.repositoryId = repositoryId;
        this.reviewId = reviewId;
        this.analysisRunId = analysisRunId;
        this.commitSha = commitSha;
        this.roles = List.copyOf(roles);
        this.seniority = seniority;
        this.scenarioCount = scenarioCount;
        this.scenarioSchemaVersion = Scenario.SCHEMA_VERSION;
        this.generatorVersion = GENERATOR_VERSION;
        this.status = ScenarioLabStatus.GENERATING;
        this.activeUserId = userId;
        this.createdAt = Instant.now();
    }

    /** Generation progress: one more scenario has been validated and saved. */
    public void scenarioReady() {
        requireStatus(ScenarioLabStatus.GENERATING);
        scenariosReady++;
    }

    /** Every scenario is validated and saved: the workspace opens. */
    public void activate() {
        requireStatus(ScenarioLabStatus.GENERATING);
        status = ScenarioLabStatus.ACTIVE;
        generatedAt = Instant.now();
    }

    /** All scenarios are submitted and evaluated; the final assessment is being written. */
    public void startFinalizing() {
        requireStatus(ScenarioLabStatus.ACTIVE);
        status = ScenarioLabStatus.FINALIZING;
    }

    /** The assessment is saved: the workspace closes and the lab becomes history. */
    public void complete() {
        requireStatus(ScenarioLabStatus.FINALIZING);
        close(ScenarioLabStatus.COMPLETED);
    }

    public void fail(String code, String userMessage) {
        if (!status.open()) {
            throw new IllegalStateException("Lab " + id + " is already " + status);
        }
        errorCode = code;
        errorMessage = userMessage == null || userMessage.length() <= 300 ? userMessage : userMessage.substring(0, 300);
        close(ScenarioLabStatus.FAILED);
    }

    public void cancel() {
        if (status != ScenarioLabStatus.GENERATING && status != ScenarioLabStatus.ACTIVE) {
            throw new IllegalStateException("Lab " + id + " can't be cancelled while " + status);
        }
        close(ScenarioLabStatus.CANCELLED);
    }

    private void close(ScenarioLabStatus finalStatus) {
        status = finalStatus;
        activeUserId = null; // frees the user's one open-lab slot
        completedAt = Instant.now();
    }

    private void requireStatus(ScenarioLabStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Lab " + id + " is " + status + ", expected " + expected);
        }
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getRepositoryId() { return repositoryId; }
    public UUID getReviewId() { return reviewId; }
    public UUID getAnalysisRunId() { return analysisRunId; }
    public String getCommitSha() { return commitSha; }
    public List<ScenarioRole> getRoles() { return roles; }
    public Seniority getSeniority() { return seniority; }
    public int getScenarioCount() { return scenarioCount; }
    public int getScenarioSchemaVersion() { return scenarioSchemaVersion; }
    public int getGeneratorVersion() { return generatorVersion; }
    public ScenarioLabStatus getStatus() { return status; }
    public UUID getActiveUserId() { return activeUserId; }
    public int getScenariosReady() { return scenariosReady; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getGeneratedAt() { return generatedAt; }
    public Instant getCompletedAt() { return completedAt; }
}
