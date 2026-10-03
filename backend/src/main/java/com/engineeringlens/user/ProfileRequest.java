package com.engineeringlens.user;

import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProfileRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull ExperienceLevel level,
        @Min(1) Integer classYear,
        WorkExperience workExperience,
        @NotNull @Size(min = 1, max = 20, message = "Add at least one language") List<@NotBlank @Size(max = 40) String> languages,
        @NotNull @Size(max = 20) List<@NotBlank @Size(max = 40) String> frameworks,
        @NotNull @Size(max = 20) List<@NotBlank @Size(max = 40) String> databases,
        @NotNull @Size(max = 20) List<@NotBlank @Size(max = 40) String> experienceAreas,
        @Size(max = 200) String githubUrl,
        @Size(max = 1000) String goals) {
}
