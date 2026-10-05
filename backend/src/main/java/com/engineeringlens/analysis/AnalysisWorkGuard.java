package com.engineeringlens.analysis;

import java.util.EnumSet;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.engineeringlens.analysis.review.ReviewRunRepository;
import com.engineeringlens.analysis.review.ReviewRunStatus;
import com.engineeringlens.repository.RepositoryWorkGuard;

/** A preparation or a review in progress reads the repository's pinned snapshot. */
@Component
class AnalysisWorkGuard implements RepositoryWorkGuard {

    private final AnalysisRunRepository analyses;
    private final ReviewRunRepository reviews;

    AnalysisWorkGuard(AnalysisRunRepository analyses, ReviewRunRepository reviews) {
        this.analyses = analyses;
        this.reviews = reviews;
    }

    @Override
    public boolean busy(UUID repositoryId) {
        return analyses.existsByRepositoryIdAndStatusIn(repositoryId, EnumSet.of(AnalysisRunStatus.QUEUED, AnalysisRunStatus.RUNNING))
                || reviews.existsByRepositoryIdAndStatusIn(repositoryId, EnumSet.of(ReviewRunStatus.QUEUED, ReviewRunStatus.RUNNING));
    }
}
