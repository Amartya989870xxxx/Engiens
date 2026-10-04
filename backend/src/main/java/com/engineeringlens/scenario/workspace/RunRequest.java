package com.engineeringlens.scenario.workspace;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The editor's current files, run against the hidden checks. */
public record RunRequest(@NotNull @Size(min = 1, max = 40) List<@Valid @NotNull FileContent> files) {
}
