package com.engineeringlens.repository;

import java.util.UUID;

/**
 * Lets modules that read a repository's pinned snapshot say "I'm working on it right now", so the repository isn't
 * moved to a new commit underneath them. Implemented by the modules that depend on repository, which keeps the
 * dependency pointing one way.
 */
public interface RepositoryWorkGuard {

    /** True while something for this repository is running that reads its pinned commit or file inventory. */
    boolean busy(UUID repositoryId);
}
