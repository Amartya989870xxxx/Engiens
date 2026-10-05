package com.engineeringlens.analysis;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, UUID> {

    Optional<AnalysisRun> findByIdAndUserId(UUID id, UUID userId);

    Optional<AnalysisRun> findFirstByRepositoryIdOrderByCreatedAtDesc(UUID repositoryId);

    List<AnalysisRun> findByStatusIn(Collection<AnalysisRunStatus> statuses);

    boolean existsByRepositoryIdAndStatusIn(UUID repositoryId, Collection<AnalysisRunStatus> statuses);

    /** A finished run of the same snapshot with the same versions and limits: its output would be identical. */
    Optional<AnalysisRun> findFirstByRepositoryIdAndStatusAndCommitShaAndProfileSchemaVersionAndRulesVersionAndContextSchemaVersionAndConfigFingerprintOrderByCreatedAtDesc(
            UUID repositoryId, AnalysisRunStatus status, String commitSha, int profileSchemaVersion, int rulesVersion,
            int contextSchemaVersion, String configFingerprint);
}
