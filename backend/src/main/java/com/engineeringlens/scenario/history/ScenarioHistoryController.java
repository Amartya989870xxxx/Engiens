package com.engineeringlens.scenario.history;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Completed labs as read-only history. Thin: identity from the JWT, everything else in ScenarioHistoryService. */
@RestController
public class ScenarioHistoryController {

    private final ScenarioHistoryService service;

    public ScenarioHistoryController(ScenarioHistoryService service) {
        this.service = service;
    }

    @GetMapping("/api/repositories/{repositoryId}/scenario-labs")
    List<LabHistoryItem> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID repositoryId) {
        return service.history(userId(jwt), repositoryId);
    }

    @GetMapping("/api/scenario-labs/{labId}/assessment")
    LabAssessmentResponse assessment(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID labId) {
        return service.assessment(userId(jwt), labId);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
