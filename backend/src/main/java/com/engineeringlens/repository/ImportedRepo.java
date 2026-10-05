package com.engineeringlens.repository;

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

/** A GitHub repository a user imported (table "repositories"). Named to avoid clashing with Spring's Repository. */
@Entity
@Table(name = "repositories")
public class ImportedRepo {

    private static final int MAX_FAILURE_REASON = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "github_owner", nullable = false)
    private String githubOwner;

    @Column(name = "github_repo_name", nullable = false)
    private String githubRepoName;

    @Column(name = "github_url", nullable = false)
    private String githubUrl;

    private String description;

    @Column(name = "default_branch", nullable = false)
    private String defaultBranch;

    /** The commit the inventory was taken from; null for imports made before commits were recorded. */
    @Column(name = "commit_sha")
    private String commitSha;

    @Column(name = "primary_language")
    private String primaryLanguage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RepositoryVisibility visibility;

    @Column(nullable = false)
    private int stars;

    @Column(nullable = false)
    private int forks;

    @Column(name = "file_count", nullable = false)
    private int fileCount;

    @Column(name = "relevant_file_count", nullable = false)
    private int relevantFileCount;

    @Column(name = "ignored_file_count", nullable = false)
    private int ignoredFileCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RepositoryStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ImportedRepo() {
    }

    public ImportedRepo(UUID userId) {
        this.userId = userId;
        this.createdAt = Instant.now();
    }

    /** Records GitHub's current metadata and (re)starts the import. Counts reset until files are saved. */
    public void startImport(String owner, String name, String url, String description, String defaultBranch,
            String primaryLanguage, RepositoryVisibility visibility, int stars, int forks) {
        this.githubOwner = owner;
        this.githubRepoName = name;
        this.githubUrl = url;
        this.description = description == null || description.length() <= 1000 ? description : description.substring(0, 1000);
        this.defaultBranch = defaultBranch;
        this.primaryLanguage = primaryLanguage;
        this.visibility = visibility;
        this.stars = stars;
        this.forks = forks;
        this.fileCount = 0;
        this.relevantFileCount = 0;
        this.ignoredFileCount = 0;
        this.failureReason = null;
        this.status = RepositoryStatus.IMPORTING;
        this.updatedAt = Instant.now();
    }

    /** GitHub's current metadata for an already-imported repository; the import status is unchanged. */
    public void refreshMetadata(String description, String defaultBranch, String primaryLanguage, RepositoryVisibility visibility,
            int stars, int forks) {
        this.description = description == null || description.length() <= 1000 ? description : description.substring(0, 1000);
        this.defaultBranch = defaultBranch;
        this.primaryLanguage = primaryLanguage;
        this.visibility = visibility;
        this.stars = stars;
        this.forks = forks;
    }

    public void markReady(String commitSha, int fileCount, int relevantFileCount, int ignoredFileCount) {
        this.commitSha = commitSha;
        this.fileCount = fileCount;
        this.relevantFileCount = relevantFileCount;
        this.ignoredFileCount = ignoredFileCount;
        this.status = RepositoryStatus.READY;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.failureReason = reason == null || reason.length() <= MAX_FAILURE_REASON ? reason : reason.substring(0, MAX_FAILURE_REASON);
        this.status = RepositoryStatus.FAILED;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getGithubOwner() { return githubOwner; }
    public String getGithubRepoName() { return githubRepoName; }
    public String getGithubUrl() { return githubUrl; }
    public String getDescription() { return description; }
    public String getDefaultBranch() { return defaultBranch; }
    public String getCommitSha() { return commitSha; }
    public String getPrimaryLanguage() { return primaryLanguage; }
    public RepositoryVisibility getVisibility() { return visibility; }
    public int getStars() { return stars; }
    public int getForks() { return forks; }
    public int getFileCount() { return fileCount; }
    public int getRelevantFileCount() { return relevantFileCount; }
    public int getIgnoredFileCount() { return ignoredFileCount; }
    public RepositoryStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public Instant getUpdatedAt() { return updatedAt; }
}
