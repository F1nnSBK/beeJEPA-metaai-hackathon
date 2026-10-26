package com.beejepa;

import com.beejepa.auth.AuthService;
import com.beejepa.auth.BeekeeperUser;
import com.beejepa.auth.PasswordHasher;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;

@MicronautTest
class AuthServiceTest {

    @Inject
    AuthService authService;

    @Inject
    PasswordHasher passwordHasher;

    @Test
    void testPasswordHasherGeneratesSaltedHash() {
        PasswordHasher.HashedResult result1 = passwordHasher.hashPassword("secret123");
        PasswordHasher.HashedResult result2 = passwordHasher.hashPassword("secret123");

        // Two hashes of the same password MUST have different salts and hashes
        Assertions.assertNotEquals(result1.salt(), result2.salt());
        Assertions.assertNotEquals(result1.hash(), result2.hash());

        // But both must verify properly against their respective salts
        Assertions.assertTrue(passwordHasher.verifyPassword("secret123", result1.hash(), result1.salt()));
        Assertions.assertTrue(passwordHasher.verifyPassword("secret123", result2.hash(), result2.salt()));
        Assertions.assertFalse(passwordHasher.verifyPassword("wrong_pass", result1.hash(), result1.salt()));
    }

    @Test
    void testDefaultBeekeeperAuthentication() {
        // Default seeded credentials
        AuthService.AuthResponse res = authService.authenticate("beekeeper", "beejepa2026");
        Assertions.assertTrue(res.success());
        Assertions.assertNotNull(res.token());
        Assertions.assertEquals("beekeeper", res.username());

        // Validate session
        Optional<BeekeeperUser> user = authService.validateSession(res.token());
        Assertions.assertTrue(user.isPresent());
        Assertions.assertEquals("beekeeper", user.get().username());

        // Wrong password fails
        AuthService.AuthResponse failRes = authService.authenticate("beekeeper", "invalid_password");
        Assertions.assertFalse(failRes.success());
    }
}
