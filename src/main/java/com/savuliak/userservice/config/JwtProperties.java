package com.savuliak.userservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Base64-encoded HMAC secret. Must be the exact same string the Auth Service
 * uses for {@code JWT_SECRET} — both services decode via Base64 before
 * {@code Keys.hmacShaKeyFor(...)}, so the byte arrays (and therefore the
 * SecretKey) are identical.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret) {
}
