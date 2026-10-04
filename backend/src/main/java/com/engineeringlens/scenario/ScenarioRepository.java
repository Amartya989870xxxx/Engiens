package com.engineeringlens.scenario;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ScenarioRepository extends JpaRepository<Scenario, UUID> {

    List<Scenario> findByLabIdOrderByPositionAsc(UUID labId);

    Optional<Scenario> findByIdAndLabId(UUID id, UUID labId);
}
