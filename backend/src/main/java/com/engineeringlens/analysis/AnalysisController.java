package com.engineeringlens.analysis;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AnalysisController {

    private final AnalysisPreparationService service;

    public AnalysisController(AnalysisPreparationService service) {
        this.service = service;
    }

    /** Prepares a repository for review (profile, signals, context). Reuses an identical earlier run. */
    @PostMapping("/api/repositories/{repositoryId}/analyses")
    AnalysisRunResponse prepare(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID repositoryId) {
        return service.prepare(userId(jwt), repositoryId);
    }

    @GetMapping("/api/repositories/{repositoryId}/analyses/latest")
    AnalysisRunResponse latest(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID repositoryId) {
        return service.latest(userId(jwt), repositoryId);
    }

    @GetMapping("/api/analyses/{id}")
    AnalysisRunResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(userId(jwt), id);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
