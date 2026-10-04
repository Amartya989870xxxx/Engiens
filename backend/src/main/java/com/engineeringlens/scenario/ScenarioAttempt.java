package com.engineeringlens.scenario;

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

/**
 * A submitted scenario: permanent history. The submission (code, approach, final run result) is fixed at
 * construction and has no setters; only the evaluation is filled in, once. It copies the lab's snapshot
 * (repository, review, commit, role, seniority) so it can be read without the workspace.
 */
@Entity
@Table(name = "scenario_attempts")
public class ScenarioAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "review_id")
    private UUID reviewId;

    @Column(name = "lab_id", nullable = false)
    private UUID labId;

    @Column(name = "scenario_id", nullable = false, unique = true)
    private UUID scenarioId;

    @Column(name = "scenario_schema_version", nullable = false)
    private int scenarioSchemaVersion;

    @Column(name = "commit_sha", nullable = false)
    private String commitSha;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScenarioRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Seniority seniority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScenarioCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkMode mode;

    @Column(name = "submitted_files_json")
    private String submittedFilesJson;

    @Column(name = "submitted_approach")
    private String submittedApproach;

    @Column(name = "run_result_json")
    private String runResultJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "evaluation_status", nullable = false)
    private EvaluationStatus evaluationStatus;

    @Column(name = "evaluation_json")
    private String evaluationJson;

    private String provider;

    private String model;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "evaluated_at")
    private Instant evaluatedAt;

    protected ScenarioAttempt() {
    }

    public ScenarioAttempt(ScenarioLab lab, Scenario scenario, WorkMode mode, String submittedFilesJson, String submittedApproach,
            String runResultJson) {
        if (!scenario.getLabId().equals(lab.getId())) {
            throw new IllegalArgumentException("Scenario " + scenario.getId() + " doesn't belong to lab " + lab.getId());
        }
        this.userId = lab.getUserId();
        this.repositoryId = lab.getRepositoryId();
        this.reviewId = lab.getReviewId();
        this.labId = lab.getId();
        this.scenarioId = scenario.getId();
        this.scenarioSchemaVersion = scenario.getSchemaVersion();
        this.commitSha = lab.getCommitSha();
        this.role = scenario.getRole();
        this.seniority = scenario.getSeniority();
        this.category = scenario.getCategory();
        this.mode = mode;
        this.submittedFilesJson = submittedFilesJson;
        this.submittedApproach = submittedApproach;
        this.runResultJson = runResultJson;
        this.evaluationStatus = EvaluationStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void evaluated(String evaluationJson, String provider, String model) {
        if (evaluationStatus == EvaluationStatus.COMPLETED) {
            throw new IllegalStateException("Attempt " + id + " is already evaluated");
        }
        this.evaluationJson = evaluationJson;
        this.provider = provider;
        this.model = model;
        this.errorCode = null;
        this.errorMessage = null;
        this.evaluationStatus = EvaluationStatus.COMPLETED;
        this.evaluatedAt = Instant.now();
    }

    public void evaluationFailed(String code, String userMessage) {
        if (evaluationStatus == EvaluationStatus.COMPLETED) {
            throw new IllegalStateException("Attempt " + id + " is already evaluated");
        }
        this.errorCode = code;
        this.errorMessage = userMessage == null || userMessage.length() <= 300 ? userMessage : userMessage.substring(0, 300);
        this.evaluationStatus = EvaluationStatus.FAILED;
    }

    /** A failed evaluation can be tried again; the submission itself is untouched. */
    public void retryEvaluation() {
        if (evaluationStatus != EvaluationStatus.FAILED) {
            throw new IllegalStateException("Only a failed evaluation can be retried");
        }
        this.evaluationStatus = EvaluationStatus.PENDING;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getRepositoryId() { return repositoryId; }
    public UUID getReviewId() { return reviewId; }
    public UUID getLabId() { return labId; }
    public UUID getScenarioId() { return scenarioId; }
    public int getScenarioSchemaVersion() { return scenarioSchemaVersion; }
    public String getCommitSha() { return commitSha; }
    public ScenarioRole getRole() { return role; }
    public Seniority getSeniority() { return seniority; }
    public ScenarioCategory getCategory() { return category; }
    public WorkMode getMode() { return mode; }
    public String getSubmittedFilesJson() { return submittedFilesJson; }
    public String getSubmittedApproach() { return submittedApproach; }
    public String getRunResultJson() { return runResultJson; }
    public EvaluationStatus getEvaluationStatus() { return evaluationStatus; }
    public String getEvaluationJson() { return evaluationJson; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getEvaluatedAt() { return evaluatedAt; }
}
