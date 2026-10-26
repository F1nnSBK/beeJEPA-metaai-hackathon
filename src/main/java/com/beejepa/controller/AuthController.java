package com.beejepa.controller;

import com.beejepa.auth.AuthService;
import com.beejepa.auth.BeekeeperUser;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.util.Map;
import java.util.Optional;

@Controller("/api/v1/auth")
@ExecuteOn(TaskExecutors.BLOCKING)
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record LoginRequest(String username, String password) {}
    public record RegisterRequest(String username, String password, String role) {}

    @Post("/login")
    public HttpResponse<AuthService.AuthResponse> login(@Body LoginRequest request) {
        AuthService.AuthResponse response = authService.authenticate(request.username(), request.password());
        if (response.success()) {
            return HttpResponse.ok(response);
        } else {
            return HttpResponse.status(HttpStatus.UNAUTHORIZED).body(response);
        }
    }

    @Post("/logout")
    public HttpResponse<Map<String, String>> logout(@Header(value = "Authorization", defaultValue = "") String authHeader) {
        authService.logout(authHeader);
        return HttpResponse.ok(Map.of("message", "Logged out successfully"));
    }

    @Get("/me")
    public HttpResponse<?> getCurrentUser(@Header(value = "Authorization", defaultValue = "") String authHeader) {
        Optional<BeekeeperUser> userOpt = authService.validateSession(authHeader);
        if (userOpt.isPresent()) {
            BeekeeperUser user = userOpt.get();
            return HttpResponse.ok(Map.of(
                    "id", user.id(),
                    "username", user.username(),
                    "role", user.role(),
                    "createdAt", user.createdAt().toString(),
                    "lastLoginAt", user.lastLoginAt() != null ? user.lastLoginAt().toString() : ""
            ));
        }
        return HttpResponse.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
    }

    @Post("/register")
    public HttpResponse<AuthService.AuthResponse> register(@Body RegisterRequest request) {
        AuthService.AuthResponse response = authService.register(request.username(), request.password(), request.role());
        if (response.success()) {
            return HttpResponse.ok(response);
        } else {
            return HttpResponse.badRequest(response);
        }
    }
}
