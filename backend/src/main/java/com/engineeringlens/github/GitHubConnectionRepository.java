package com.engineeringlens.github;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GitHubConnectionRepository extends JpaRepository<GitHubConnection, UUID> {
}
