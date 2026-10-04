package com.engineeringlens.scenario.workspace;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** One file as the editor sends it. */
public record FileContent(@NotBlank @Size(max = 200) String path, @NotNull @Size(max = 65536) String content) {
}
