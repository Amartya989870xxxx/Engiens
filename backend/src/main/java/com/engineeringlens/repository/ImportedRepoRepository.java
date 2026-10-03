package com.engineeringlens.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportedRepoRepository extends JpaRepository<ImportedRepo, UUID> {

    /** Every read goes through the owner's id, so one user can never load another user's repository. */
    Optional<ImportedRepo> findByIdAndUserId(UUID id, UUID userId);

    /** GitHub owner and repository names are case-insensitive: "Octocat/hello-world" is the same repo. */
    Optional<ImportedRepo> findByUserIdAndGithubOwnerIgnoreCaseAndGithubRepoNameIgnoreCase(UUID userId, String owner, String name);

    List<ImportedRepo> findByUserIdOrderByUpdatedAtDesc(UUID userId);
}
