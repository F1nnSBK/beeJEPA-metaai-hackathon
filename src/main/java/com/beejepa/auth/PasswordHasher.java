package com.beejepa.auth;

import jakarta.inject.Singleton;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

@Singleton
public class PasswordHasher {

    private static final int ITERATIONS = 65536;
    private static final int KEY_LENGTH = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private final SecureRandom random = new SecureRandom();

    public record HashedResult(String hash, String salt) {}

    public HashedResult hashPassword(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        String hash = hashWithSalt(password, salt);
        return new HashedResult(hash, Base64.getEncoder().encodeToString(salt));
    }

    public boolean verifyPassword(String password, String expectedHash, String base64Salt) {
        byte[] salt = Base64.getDecoder().decode(base64Salt);
        String calculatedHash = hashWithSalt(password, salt);
        return MessageDigest.isEqual(
                calculatedHash.getBytes(),
                expectedHash.getBytes()
        );
    }

    private String hashWithSalt(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            byte[] hash = factory.generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Error hashing password", e);
        }
    }
}
