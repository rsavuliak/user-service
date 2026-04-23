package com.savuliak.userservice.support;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Helper for test code — signs tokens with the same Base64-decoded key
 * the runtime parser expects. Matches the Auth Service's derivation path.
 */
public final class TestJwt {

    public static final String SECRET_BASE64 =
            "dGVzdC1zZWNyZXQtZm9yLWp3dC12YWxpZGF0aW9uLW5vdC1yZWFsLWhzMjU2LTQ4Yg==";

    private TestJwt() {
    }

    public static SecretKey key() {
        return Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET_BASE64));
    }

    public static String valid(UUID id, String email, String provider, boolean emailVerified) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(id.toString())
                .claim("email", email)
                .claim("provider", provider)
                .claim("emailVerified", emailVerified)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(900)))
                .signWith(key(), Jwts.SIG.HS256)
                .compact();
    }

    public static String expired(UUID id, String email) {
        Instant past = Instant.now().minusSeconds(7200);
        return Jwts.builder()
                .subject(id.toString())
                .claim("email", email)
                .claim("provider", "local")
                .claim("emailVerified", true)
                .issuedAt(Date.from(past))
                .expiration(Date.from(past.plusSeconds(60)))
                .signWith(key(), Jwts.SIG.HS256)
                .compact();
    }

    public static String wrongSignature(UUID id, String email) {
        SecretKey other = Keys.hmacShaKeyFor(Base64.getDecoder().decode(
                "b3RoZXItc2VjcmV0LW5vdC10aGUtb25lLXdlLXVzZS1mb3ItaHMyNTYtNDgtYnl0ZXM="));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(id.toString())
                .claim("email", email)
                .claim("provider", "local")
                .claim("emailVerified", true)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(900)))
                .signWith(other, Jwts.SIG.HS256)
                .compact();
    }

    public static String missingSubject(String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim("email", email)
                .claim("provider", "local")
                .claim("emailVerified", true)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(900)))
                .signWith(key(), Jwts.SIG.HS256)
                .compact();
    }
}
