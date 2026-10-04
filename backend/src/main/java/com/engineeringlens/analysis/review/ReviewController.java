package com.engineeringlens.analysis.review;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Thin: identity from the JWT, everything else in ReviewService. */
@RestController
public class ReviewController {

    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    /** Starts a review (or returns an identical finished/in-progress one). Poll GET /api/reviews/{id}. */
    @PostMapping("/api/repositories/{repositoryId}/reviews")
    ReviewRunResponse start(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID repositoryId,
            @RequestParam(defaultValue = "false") boolean regenerate) {
        return service.start(userId(jwt), repositoryId, regenerate);
    }

    @GetMapping("/api/repositories/{repositoryId}/reviews")
    List<ReviewRunResponse> history(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID repositoryId) {
        return service.history(userId(jwt), repositoryId);
    }

    @GetMapping("/api/repositories/{repositoryId}/reviews/latest")
    ReviewRunResponse latest(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID repositoryId) {
        return service.latest(userId(jwt), repositoryId);
    }

    @GetMapping("/api/reviews")
    List<ReviewRunResponse> recent(@AuthenticationPrincipal Jwt jwt) {
        return service.recent(userId(jwt));
    }

    @GetMapping("/api/reviews/{id}")
    ReviewRunResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(userId(jwt), id);
    }

    @GetMapping("/api/reviews/{id}/excerpt")
    ExcerptResponse excerpt(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @RequestParam String file,
            @RequestParam int lineStart, @RequestParam int lineEnd) {
        return service.excerpt(userId(jwt), id, file, lineStart, lineEnd);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
