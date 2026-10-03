package com.engineeringlens.analysis;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisArtifactRepository extends JpaRepository<AnalysisArtifact, UUID> {
}
