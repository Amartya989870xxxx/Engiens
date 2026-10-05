package com.engineeringlens.scenario.lab;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/** Thin: identity from the JWT, everything else in ScenarioLabService. */
@RestController
public class ScenarioLabController {

    private final ScenarioLabService service;

    public ScenarioLabController(ScenarioLabService service) {
        this.service = service;
    }

    @PostMapping("/api/scenario-labs")
    ScenarioLabResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateScenarioLabRequest request) {
        return service.create(userId(jwt), request);
    }

    /** The open lab, or 204 when there is none (Scenario Lab then shows its start screen). */
    @GetMapping("/api/scenario-labs/active")
    ResponseEntity<ScenarioLabResponse> active(@AuthenticationPrincipal Jwt jwt) {
        return service.active(userId(jwt)).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/api/scenario-labs/capacity")
    ScenarioLabService.Capacity capacity() {
        return service.capacity();
    }

    @GetMapping("/api/scenario-labs/{id}")
    ScenarioLabResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(userId(jwt), id);
    }

    @PostMapping("/api/scenario-labs/{id}/cancel")
    ScenarioLabResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.cancel(userId(jwt), id);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
