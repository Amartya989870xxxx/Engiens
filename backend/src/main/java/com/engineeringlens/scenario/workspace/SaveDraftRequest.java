package com.engineeringlens.scenario.workspace;

import java.util.List;

import com.engineeringlens.scenario.WorkMode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Autosave: the current mode plus code and/or approach. A null part is left unchanged. */
public record SaveDraftRequest(@NotNull WorkMode mode, @Size(max = 40) List<@Valid @NotNull FileContent> files,
        @Size(max = 20000, message = "Keep your approach under 20,000 characters") String approach) {
}
