package com.engineeringlens.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/repositories")
public class RepositoryController {

    private final RepositoryImportService service;

    public RepositoryController(RepositoryImportService service) {
        this.service = service;
    }

    /** Imports a repository, or returns the existing one if this user already imported it. */
    @PostMapping("/import")
    RepositoryResponse importRepository(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ImportRepositoryRequest request) {
        return service.importRepository(userId(jwt), request.url());
    }

    @GetMapping
    List<RepositoryListItem> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(userId(jwt));
    }

    @GetMapping("/{id}")
    RepositoryResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(userId(jwt), id);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
