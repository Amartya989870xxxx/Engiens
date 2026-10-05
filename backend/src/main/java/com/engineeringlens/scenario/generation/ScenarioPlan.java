package com.engineeringlens.scenario.generation;

import java.util.List;

import com.engineeringlens.scenario.ExecutionCapability;
import com.engineeringlens.scenario.ScenarioCategory;
import com.engineeringlens.scenario.ScenarioDifficulty;
import com.engineeringlens.scenario.ScenarioLanguage;
import com.engineeringlens.scenario.ScenarioRole;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Step 1's answer: short outlines of candidate scenarios, each grounded in real files of this repository. */
public record ScenarioPlan(@NotNull Integer scenarioPlanSchemaVersion, @NotBlank String repositorySummary,
        @NotNull @Size(min = 1) List<@Valid @NotNull Outline> outlines) {

    public static final int SCHEMA_VERSION = 1;

    /**
     * @param role            the primary role the scenario is written for
     * @param applicableRoles other selected roles it genuinely involves (a cross-boundary problem), or null
     */
    public record Outline(@NotBlank String key, @NotBlank @Size(max = 200) String title, @NotNull ScenarioRole role,
            List<@NotNull ScenarioRole> applicableRoles,
            @NotNull ScenarioCategory category, @NotNull ScenarioDifficulty difficulty, @NotNull ExecutionCapability mode,
            ScenarioLanguage language, @NotBlank String problem, @NotNull @Size(min = 1) List<@Valid @NotNull Grounding> groundedIn,
            @NotNull List<@NotBlank String> expectedConcepts, @NotBlank String whyItFitsTheLevel) {

        Outline withKey(String newKey) {
            return new Outline(newKey, title, role, applicableRoles, category, difficulty, mode, language, problem, groundedIn,
                    expectedConcepts, whyItFitsTheLevel);
        }

        Outline withMode(ExecutionCapability newMode, ScenarioLanguage newLanguage) {
            return new Outline(key, title, role, applicableRoles, category, difficulty, newMode, newLanguage, problem, groundedIn,
                    expectedConcepts, whyItFitsTheLevel);
        }
    }

    public record Grounding(@NotBlank String file, Integer lineStart, Integer lineEnd, @NotBlank String why) {
    }
}
