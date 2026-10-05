package com.engineeringlens.progress;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Progress, read-only. Thin: identity from the JWT, everything else in ProgressService. */
@RestController
public class ProgressController {

    private final ProgressService service;

    public ProgressController(ProgressService service) {
        this.service = service;
    }

    /** Indicators per engineering area, across all repositories or (with repositoryId) one. */
    @GetMapping("/api/progress")
    ProgressResponse overview(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID repositoryId) {
        return service.overview(userId(jwt), repositoryId);
    }

    /** Completed reviews and labs, newest first, 20 per page. */
    @GetMapping("/api/progress/history")
    ProgressHistoryResponse history(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) UUID repositoryId,
            @RequestParam(defaultValue = "0") int page) {
        return service.history(userId(jwt), repositoryId, page);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
