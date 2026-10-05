package com.engineeringlens.analysis.review;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewRunRepository extends JpaRepository<ReviewRun, UUID> {

    Optional<ReviewRun> findByIdAndUserId(UUID id, UUID userId);

    List<ReviewRun> findByRepositoryIdAndUserIdOrderByCreatedAtDesc(UUID repositoryId, UUID userId);

    List<ReviewRun> findTop10ByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, ReviewRunStatus status);

    Optional<ReviewRun> findFirstByRepositoryIdAndUserIdAndStatusOrderByCreatedAtDesc(UUID repositoryId, UUID userId,
            ReviewRunStatus status);

    /** A finished review of the same prepared context with the same rubric and schema: reusable as-is. */
    Optional<ReviewRun> findFirstByAnalysisRunIdAndStatusAndRubricVersionAndReviewSchemaVersionOrderByCreatedAtDesc(
            UUID analysisRunId, ReviewRunStatus status, int rubricVersion, int reviewSchemaVersion);

    Optional<ReviewRun> findFirstByAnalysisRunIdAndStatusInOrderByCreatedAtDesc(UUID analysisRunId, Collection<ReviewRunStatus> statuses);

    List<ReviewRun> findByStatusIn(Collection<ReviewRunStatus> statuses);

    /** Reviews that spent AI quota recently (failed ones didn't), for the daily cap. */
    List<ReviewRun> findByUserIdAndCreatedAtAfterAndStatusNotOrderByCreatedAtAsc(UUID userId, Instant after, ReviewRunStatus status);

    boolean existsByRepositoryIdAndStatusIn(UUID repositoryId, Collection<ReviewRunStatus> statuses);

    /** Progress evidence: the user's newest finished reviews, across repositories or in one. */
    List<ReviewRun> findTop50ByUserIdAndStatusOrderByCompletedAtDesc(UUID userId, ReviewRunStatus status);

    List<ReviewRun> findTop50ByUserIdAndRepositoryIdAndStatusOrderByCompletedAtDesc(UUID userId, UUID repositoryId, ReviewRunStatus status);
}
