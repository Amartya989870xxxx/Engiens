package com.engineeringlens.auth;

import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.engineeringlens.common.ApiException;
import com.engineeringlens.user.ProfileService;
import com.engineeringlens.user.User;
import com.engineeringlens.user.UserRepository;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final ProfileService profiles;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt, ProfileService profiles) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.profiles = profiles;
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String email = normalize(req.email());
        if (users.existsByEmail(email)) {
            throw emailTaken();
        }
        User user;
        try {
            user = users.saveAndFlush(new User(email, encoder.encode(req.password()), req.name().trim()));
        } catch (DataIntegrityViolationException e) {
            // Two concurrent registrations can both pass the exists check; the unique index decides.
            throw emailTaken();
        }
        return new AuthResponse(jwt.issue(user), toResponse(user));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        User user = users.findByEmail(normalize(req.email())).orElse(null);
        // Same error for unknown email and wrong password so accounts can't be enumerated.
        if (user == null || !encoder.matches(req.password(), user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Incorrect email or password");
        }
        return new AuthResponse(jwt.issue(user), toResponse(user));
    }

    @Transactional(readOnly = true)
    public UserResponse me(UUID userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "User no longer exists"));
        return toResponse(user);
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), profiles.exists(user.getId()));
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException emailTaken() {
        return new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "An account with this email already exists");
    }
}
