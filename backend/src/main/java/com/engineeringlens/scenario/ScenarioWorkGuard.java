package com.engineeringlens.scenario;

import java.util.EnumSet;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.engineeringlens.repository.RepositoryWorkGuard;

/** Generating or finalizing a lab reads the repository's file inventory; an active lab only uses its own snapshot. */
@Component
class ScenarioWorkGuard implements RepositoryWorkGuard {

    private final ScenarioLabRepository labs;

    ScenarioWorkGuard(ScenarioLabRepository labs) {
        this.labs = labs;
    }

    @Override
    public boolean busy(UUID repositoryId) {
        return labs.existsByRepositoryIdAndStatusIn(repositoryId, EnumSet.of(ScenarioLabStatus.GENERATING, ScenarioLabStatus.FINALIZING));
    }
}
