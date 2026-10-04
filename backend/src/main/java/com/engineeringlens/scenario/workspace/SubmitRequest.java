package com.engineeringlens.scenario.workspace;

import java.util.List;

import com.engineeringlens.scenario.WorkMode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The final answer. Both parts are kept: in CODE mode the code is the primary evidence, in APPROACH mode the
 * reasoning is.
 */
public record SubmitRequest(@NotNull(message = "Choose code or approach") WorkMode mode, @Size(max = 40) List<@Valid @NotNull FileContent> files,
        @Size(max = 20000, message = "Keep your approach under 20,000 characters") String approach) {
}
