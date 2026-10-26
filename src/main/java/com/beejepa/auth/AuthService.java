package com.beejepa.auth;

import com.beejepa.state.StateRepository;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
@ExecuteOn(TaskExecutors.BLOCKING)
public class AuthService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);

    private final StateRepository repository;
    private final PasswordHasher passwordHasher;

    // Simple in-memory session token store (token -> username)
    private final Map<String, String> activeSessions = new ConcurrentHashMap<>();

    public AuthService(StateRepository repository, PasswordHasher passwordHasher) {
        this.repository = repository;
        this.passwordHasher = passwordHasher;
    }

    @PostConstruct
    public void seedDefaultBeekeeperIfEmpty() {
        if (repository.countBeekeepers() == 0) {
            String defaultUsername = "beekeeper";
            String defaultPassword = System.getenv().getOrDefault("DEFAULT_BEEKEEPER_PASSWORD", "beejepa2026");
            LOG.info("No beekeeper accounts found in database. Initializing default beekeeper: '{}'", defaultUsername);

            PasswordHasher.HashedResult hashed = passwordHasher.hashPassword(defaultPassword);
            BeekeeperUser user = new BeekeeperUser(
                    UUID.randomUUID().toString(),
                    defaultUsername,
                    hashed.hash(),
                    hashed.salt(),
                    "BEEKEEPER_ADMIN",
                    Instant.now(),
                    null
            );
            repository.saveBeekeeper(user);
            LOG.info("Default beekeeper account successfully created with secure salted hash.");
        }
    }

    @io.micronaut.core.annotation.Introspected
    @io.micronaut.serde.annotation.Serdeable
    public record AuthResponse(
            boolean success,
            String message,
            String token,
            String username,
            String role
    ) {}

    public AuthResponse authenticate(String username, String rawPassword) {
        if (username == null || rawPassword == null) {
            return new AuthResponse(false, "Username and password are required", null, null, null);
        }

        Optional<BeekeeperUser> userOpt = repository.findBeekeeperByUsername(username.trim());
        if (userOpt.isEmpty()) {
            return new AuthResponse(false, "Invalid credentials", null, null, null);
        }

        BeekeeperUser user = userOpt.get();
        boolean valid = passwordHasher.verifyPassword(rawPassword, user.passwordHash(), user.salt());
        if (!valid) {
            return new AuthResponse(false, "Invalid credentials", null, null, null);
        }

        repository.updateBeekeeperLoginTime(user.id());
        String token = "bk_session_" + UUID.randomUUID().toString().replace("-", "");
        activeSessions.put(token, user.username());

        LOG.info("Beekeeper '{}' successfully authenticated.", user.username());
        return new AuthResponse(true, "Authentication successful", token, user.username(), user.role());
    }

    public Optional<BeekeeperUser> validateSession(String token) {
        if (token == null) return Optional.empty();
        String cleanToken = token.startsWith("Bearer ") ? token.substring(7).trim() : token.trim();
        String username = activeSessions.get(cleanToken);
        if (username == null) return Optional.empty();
        return repository.findBeekeeperByUsername(username);
    }

    public void logout(String token) {
        if (token != null) {
            String cleanToken = token.startsWith("Bearer ") ? token.substring(7).trim() : token.trim();
            activeSessions.remove(cleanToken);
        }
    }

    public AuthResponse register(String username, String password, String role) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return new AuthResponse(false, "Username and password cannot be empty", null, null, null);
        }

        if (repository.findBeekeeperByUsername(username.trim()).isPresent()) {
            return new AuthResponse(false, "Username already exists", null, null, null);
        }

        PasswordHasher.HashedResult hashed = passwordHasher.hashPassword(password);
        BeekeeperUser newUser = new BeekeeperUser(
                UUID.randomUUID().toString(),
                username.trim(),
                hashed.hash(),
                hashed.salt(),
                role != null ? role : "BEEKEEPER",
                Instant.now(),
                null
        );
        repository.saveBeekeeper(newUser);
        return authenticate(username.trim(), password);
    }
}
