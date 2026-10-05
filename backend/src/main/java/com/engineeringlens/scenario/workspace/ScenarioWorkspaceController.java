package com.engineeringlens.scenario.workspace;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.engineeringlens.scenario.execution.RunResult;

import jakarta.validation.Valid;

/** The open lab's workspace. Thin: identity from the JWT, everything else in ScenarioWorkspaceService. */
@RestController
@RequestMapping("/api/scenario-labs/{labId}")
public class ScenarioWorkspaceController {

    private final ScenarioWorkspaceService service;

    public ScenarioWorkspaceController(ScenarioWorkspaceService service) {
        this.service = service;
    }

    @GetMapping("/scenarios/{scenarioId}")
    ScenarioDetailResponse detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID labId, @PathVariable UUID scenarioId) {
        return service.detail(userId(jwt), labId, scenarioId);
    }

    @PutMapping("/scenarios/{scenarioId}/draft")
    ResponseEntity<Void> saveDraft(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID labId, @PathVariable UUID scenarioId,
            @Valid @RequestBody SaveDraftRequest request) {
        service.saveDraft(userId(jwt), labId, scenarioId, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/scenarios/{scenarioId}/run")
    RunResult run(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID labId, @PathVariable UUID scenarioId,
            @Valid @RequestBody RunRequest request) {
        return service.run(userId(jwt), labId, scenarioId, request);
    }

    @PostMapping("/scenarios/{scenarioId}/submit")
    AttemptStatusResponse submit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID labId, @PathVariable UUID scenarioId,
            @Valid @RequestBody SubmitRequest request) {
        return service.submit(userId(jwt), labId, scenarioId, request);
    }

    @PostMapping("/scenarios/{scenarioId}/attempt/retry-evaluation")
    AttemptStatusResponse retryEvaluation(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID labId, @PathVariable UUID scenarioId) {
        return service.retryEvaluation(userId(jwt), labId, scenarioId);
    }

    /** Finish the lab with the scenarios submitted so far; poll the lab until COMPLETED. */
    @PostMapping("/finish")
    ResponseEntity<Void> finishEarly(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID labId) {
        service.finishEarly(userId(jwt), labId);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/finalize")
    ResponseEntity<Void> retryFinalization(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID labId) {
        service.retryFinalization(userId(jwt), labId);
        return ResponseEntity.accepted().build();
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
