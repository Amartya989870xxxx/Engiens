package com.engineeringlens.github;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "github_connect_states")
public class GitHubConnectState {

    @Id
    private String state;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected GitHubConnectState() {
    }

    public GitHubConnectState(String state, UUID userId, Instant expiresAt) {
        this.state = state;
        this.userId = userId;
        this.expiresAt = expiresAt;
    }

    public UUID getUserId() {
        return userId;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
