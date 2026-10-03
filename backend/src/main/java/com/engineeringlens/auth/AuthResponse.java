package com.engineeringlens.auth;

public record AuthResponse(String token, UserResponse user) {
}
