package com.engineeringlens.github;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "github_connections")
public class GitHubConnection {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "installation_id", nullable = false)
    private long installationId;

    @Column(name = "github_login", nullable = false)
    private String githubLogin;

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt;

    protected GitHubConnection() {
    }

    public GitHubConnection(UUID userId) {
        this.userId = userId;
    }

    public void connect(long installationId, String githubLogin) {
        this.installationId = installationId;
        this.githubLogin = githubLogin;
        this.connectedAt = Instant.now();
    }

    public long getInstallationId() {
        return installationId;
    }

    public String getGithubLogin() {
        return githubLogin;
    }
}
