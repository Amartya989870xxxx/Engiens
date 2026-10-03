package com.engineeringlens.user;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService service;

    public ProfileController(ProfileService service) {
        this.service = service;
    }

    @GetMapping
    ProfileResponse get(@AuthenticationPrincipal Jwt jwt) {
        return service.get(UUID.fromString(jwt.getSubject()));
    }

    @PutMapping
    ProfileResponse put(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProfileRequest request) {
        return service.save(UUID.fromString(jwt.getSubject()), request);
    }
}
