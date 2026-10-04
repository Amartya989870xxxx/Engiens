package com.engineeringlens.analysis.review;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StoredReviewRepository extends JpaRepository<StoredReview, UUID> {
}
