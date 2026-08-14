package com.bms.license;

import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates a random 256-bit signing key on first run and persists it next to
 * the license file (C:/LumiPOS/jwt.secret). Every install gets its own key, so
 * a token forged against one customer's JWT cannot be replayed against another.
 * This replaces the hard-coded `jwt.secret` shipped in application.yml.
 */
@Service
public class JwtSecretService {

    private static final Path SECRET_FILE = Path.of("C:/LumiPOS/jwt.secret");

    private final SecureRandom random = new SecureRandom();
    private volatile String cachedSecret;

    public String getSecret() {
        String secret = cachedSecret;
        if (secret == null) {
            secret = loadOrCreate();
            cachedSecret = secret;
        }
        return secret;
    }

    private synchronized String loadOrCreate() {
        String secret = cachedSecret;
        if (secret != null) {
            return secret;
        }
        try {
            Files.createDirectories(SECRET_FILE.getParent());
            if (Files.exists(SECRET_FILE)) {
                String existing = Files.readString(SECRET_FILE).trim();
                if (!existing.isEmpty()) {
                    cachedSecret = existing;
                    return existing;
                }
            }
            byte[] keyBytes = new byte[32];
            random.nextBytes(keyBytes);
            String generated = Base64.getEncoder().encodeToString(keyBytes);
            Files.writeString(SECRET_FILE, generated);
            cachedSecret = generated;
            return generated;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load or create per-install JWT secret", e);
        }
    }
}
