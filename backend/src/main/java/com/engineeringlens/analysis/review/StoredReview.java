package com.engineeringlens.analysis.review;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The validated review JSON of a completed run (table "reviews"). Never prompts or source code. */
@Entity
@Table(name = "reviews")
public class StoredReview {

    @Id
    @Column(name = "review_run_id")
    private UUID reviewRunId;

    @Column(name = "review_json", nullable = false)
    private String reviewJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StoredReview() {
    }

    StoredReview(UUID reviewRunId, String reviewJson) {
        this.reviewRunId = reviewRunId;
        this.reviewJson = reviewJson;
        this.createdAt = Instant.now();
    }

    public String getReviewJson() {
        return reviewJson;
    }
}
