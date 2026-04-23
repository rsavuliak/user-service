package com.savuliak.userservice.config;

import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Base64;

@Component
public class StartupValidator {

    private static final int MIN_HS256_KEY_BYTES = 32;
    private static final int MIN_INTERNAL_API_KEY_CHARS = 32;

    private final JwtProperties jwt;
    private final InternalApiProperties internal;
    private final CorsProperties cors;

    public StartupValidator(JwtProperties jwt, InternalApiProperties internal, CorsProperties cors) {
        this.jwt = jwt;
        this.internal = internal;
        this.cors = cors;
    }

    @PostConstruct
    void validate() {
        validateJwtSecret();
        validateInternalApiKey();
        validateCorsOrigins();
    }

    private void validateJwtSecret() {
        String secret = jwt.secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET must be set (Base64-encoded HMAC key, same as the Auth Service)");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "JWT_SECRET must be a valid Base64 string (same encoding as the Auth Service)", e);
        }
        if (decoded.length < MIN_HS256_KEY_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET must decode to at least " + MIN_HS256_KEY_BYTES
                            + " bytes for HS256; got " + decoded.length);
        }
    }

    private void validateInternalApiKey() {
        String key = internal.apiKey();
        if (key == null || key.length() < MIN_INTERNAL_API_KEY_CHARS) {
            throw new IllegalStateException(
                    "INTERNAL_API_KEY must be set and at least "
                            + MIN_INTERNAL_API_KEY_CHARS + " characters long");
        }
    }

    private void validateCorsOrigins() {
        if (cors.allowedOrigins() == null || cors.allowedOrigins().isEmpty()) {
            throw new IllegalStateException(
                    "CORS_ALLOWED_ORIGINS must contain at least one origin");
        }
    }
}
