package com.engineeringlens.scenario;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ScenarioLabAssessmentRepository extends JpaRepository<ScenarioLabAssessment, UUID> {
}
