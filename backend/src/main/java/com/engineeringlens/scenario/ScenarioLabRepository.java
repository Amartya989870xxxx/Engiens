package com.engineeringlens.scenario;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ScenarioLabRepository extends JpaRepository<ScenarioLab, UUID> {

    Optional<ScenarioLab> findByIdAndUserId(UUID id, UUID userId);

    /** The user's open lab (GENERATING, ACTIVE or FINALIZING), if any: there is at most one. */
    Optional<ScenarioLab> findByActiveUserId(UUID userId);

    /** Completed labs of one repository, newest first: the repository's Scenario Lab history. */
    List<ScenarioLab> findByRepositoryIdAndUserIdAndStatusOrderByCompletedAtDesc(UUID repositoryId, UUID userId, ScenarioLabStatus status);

    List<ScenarioLab> findByStatusIn(Collection<ScenarioLabStatus> statuses);
}
