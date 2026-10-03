package com.engineeringlens.analysis.source;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.engineeringlens.github.GitHubRepositoryReader;

/**
 * Reads repository files through the github module. Tokens never pass through here: the github
 * module's FileSession holds any installation token privately and only hands back file bytes.
 */
@Component
class GitHubSourceReader implements RepositorySourceReader {

    private final GitHubRepositoryReader github;

    GitHubSourceReader(GitHubRepositoryReader github) {
        this.github = github;
    }

    @Override
    public SourceSnapshot open(UUID userId, String owner, String name, String branch, boolean privateRepo, String commitSha) {
        GitHubRepositoryReader.FileSession session = github.openFiles(userId, owner, name, privateRepo);
        String sha = commitSha != null ? commitSha : session.resolveCommit(branch);
        boolean pinned = commitSha != null;
        return new SourceSnapshot() {
            @Override
            public String commitSha() {
                return sha;
            }

            @Override
            public boolean pinnedAtImport() {
                return pinned;
            }

            @Override
            public byte[] read(String path) {
                return session.read(sha, path);
            }
        };
    }
}
