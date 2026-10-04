package com.engineeringlens.scenario;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ScenarioAttemptRepository extends JpaRepository<ScenarioAttempt, UUID> {

    List<ScenarioAttempt> findByLabIdOrderByCreatedAtAsc(UUID labId);

    Optional<ScenarioAttempt> findByScenarioId(UUID scenarioId);

    Optional<ScenarioAttempt> findByIdAndUserId(UUID id, UUID userId);

    List<ScenarioAttempt> findByEvaluationStatus(EvaluationStatus status);

    long countByLabIdAndEvaluationStatusIn(UUID labId, Collection<EvaluationStatus> statuses);
}
