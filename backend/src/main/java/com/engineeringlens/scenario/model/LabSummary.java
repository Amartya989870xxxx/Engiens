package com.engineeringlens.scenario.model;

import java.util.List;

import com.engineeringlens.analysis.common.Confidence;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The neutral overall picture of a completed lab, written from the per-scenario verdicts and the lab's target
 * role and seniority only. Like every assessment in Engiens, it never sees the developer's profile.
 */
public record LabSummary(@NotNull Integer labSummarySchemaVersion, @Valid @NotNull Overall overallAssessment,
        @NotNull List<@NotBlank String> strengths, @NotNull List<@NotBlank String> growthAreas, @NotNull List<@NotBlank String> limitations) {

    public static final int SCHEMA_VERSION = 1;

    /** @param engineeringLevel a description against the target seniority, e.g. "meets SDE2 expectations on…", never a score */
    public record Overall(@NotBlank String summary, @NotBlank String engineeringLevel, @NotNull Confidence confidence) {
    }
}
