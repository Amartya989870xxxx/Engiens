package com.engineeringlens.repository;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The repository's GitHub link. The user comes from the JWT, never from the request body. */
public record ImportRepositoryRequest(@NotBlank(message = "Enter a GitHub repository link") @Size(max = 300) String url) {
}
