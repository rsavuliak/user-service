package com.savuliak.userservice.security;

import com.savuliak.userservice.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.UUID;

/**
 * Parses and validates HS256 JWTs issued by the Auth Service. The key
 * derivation path mirrors the Auth Service exactly:
 *   byte[] keyBytes = Base64.getDecoder().decode(secret)
 *   SecretKey key   = Keys.hmacShaKeyFor(keyBytes)
 * — so any token signed against the same JWT_SECRET validates here.
 */
@Component
public class JwtTokenParser {

    private final SecretKey key;

    public JwtTokenParser(JwtProperties jwtProperties) {
        byte[] keyBytes = Base64.getDecoder().decode(jwtProperties.secret());
        this.key = Keys.hmacShaKeyFor(keyBytes);
    }

    public AuthenticatedUser parse(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            throw new InvalidJwtException("Invalid token", e);
        }

        String sub = claims.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new InvalidJwtException("Missing sub claim");
        }
        UUID id;
        try {
            id = UUID.fromString(sub);
        } catch (IllegalArgumentException e) {
            throw new InvalidJwtException("sub is not a UUID", e);
        }

        String email = claims.get("email", String.class);
        if (email == null || email.isBlank()) {
            throw new InvalidJwtException("Missing email claim");
        }

        String provider = claims.get("provider", String.class);
        Boolean emailVerified = claims.get("emailVerified", Boolean.class);

        return new AuthenticatedUser(
                id,
                email,
                Boolean.TRUE.equals(emailVerified),
                provider);
    }
}
