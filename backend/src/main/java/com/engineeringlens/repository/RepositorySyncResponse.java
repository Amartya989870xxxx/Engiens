package com.engineeringlens.repository;

/**
 * The result of checking a repository for new commits.
 *
 * @param changed        true when the default branch had moved and the repository now points at its new commit
 * @param previousCommit the commit the repository was pinned to before (equal to the current one when unchanged)
 */
public record RepositorySyncResponse(RepositoryResponse repository, boolean changed, String previousCommit) {
}
