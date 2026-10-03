package com.engineeringlens.github;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface GitHubConnectStateRepository extends JpaRepository<GitHubConnectState, String> {

    @Modifying
    @Transactional
    void deleteByUserId(UUID userId);
}
