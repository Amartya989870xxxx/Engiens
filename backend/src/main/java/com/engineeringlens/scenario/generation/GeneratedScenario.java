package com.engineeringlens.scenario.generation;

import java.util.List;

import com.engineeringlens.scenario.ScenarioLanguage;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Step 2's answer for one outline, as the model returns it. After validation it is split into the stored
 * public document, workspace, harness and reference. Code parts are present exactly for CODE scenarios.
 */
public record GeneratedScenario(
        @NotNull Integer scenarioSchemaVersion,
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 600) String summary,
        @NotBlank String incident,
        @NotBlank String context,
        @NotBlank String task,
        @NotNull List<@NotBlank String> expectedBehaviour,
        @NotNull List<@NotBlank String> constraints,
        @NotNull List<@Valid @NotNull Evidence> evidence,
        @NotNull @Size(min = 1) List<@NotBlank String> expectedConcepts,
        @NotNull @Size(min = 2, max = 8) List<@Valid @NotNull Criterion> rubric,
        @NotBlank String referenceReasoning,
        @Valid Workspace workspace,
        @Valid Checks checks,
        @Valid ReferenceSolution referenceSolution) {

    public record Evidence(@NotBlank String file, Integer lineStart, Integer lineEnd, @NotBlank String explanation) {
    }

    public record Criterion(@NotBlank String criterion, @NotBlank String whatGoodLooksLike) {
    }

    public record Workspace(@NotNull ScenarioLanguage language, @NotNull @Size(min = 1, max = 8) List<@Valid @NotNull File> files) {
    }

    public record File(@NotBlank String path, @NotNull String content, Boolean editable) {
    }

    /** @param source the full checks file, written against the Engiens check API for the language */
    public record Checks(@NotBlank String source, @NotNull @Size(min = 2, max = 8) List<@NotBlank String> checkNames) {
    }

    public record ReferenceSolution(@NotNull @Size(min = 1) List<@Valid @NotNull SolutionFile> files, @NotBlank String explanation) {
    }

    public record SolutionFile(@NotBlank String path, @NotNull String content) {
    }
}
