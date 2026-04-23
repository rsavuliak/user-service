package com.savuliak.userservice.security;

import com.savuliak.userservice.config.JwtProperties;
import com.savuliak.userservice.support.TestJwt;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenParserTest {

    private final JwtTokenParser parser = new JwtTokenParser(new JwtProperties(TestJwt.SECRET_BASE64));

    @Test
    void parsesValidToken() {
        UUID id = UUID.randomUUID();
        String token = TestJwt.valid(id, "u@example.com", "local", true);

        AuthenticatedUser user = parser.parse(token);

        assertThat(user.id()).isEqualTo(id);
        assertThat(user.email()).isEqualTo("u@example.com");
        assertThat(user.provider()).isEqualTo("local");
        assertThat(user.emailVerified()).isTrue();
    }

    @Test
    void rejectsExpiredToken() {
        String token = TestJwt.expired(UUID.randomUUID(), "u@example.com");

        assertThatThrownBy(() -> parser.parse(token))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejectsWrongSignature() {
        String token = TestJwt.wrongSignature(UUID.randomUUID(), "u@example.com");

        assertThatThrownBy(() -> parser.parse(token))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejectsMalformed() {
        assertThatThrownBy(() -> parser.parse("not-a-jwt"))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejectsMissingSubject() {
        String token = TestJwt.missingSubject("u@example.com");

        assertThatThrownBy(() -> parser.parse(token))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void rejectsNonUuidSubject() {
        String token = Jwts.builder()
                .subject("not-a-uuid")
                .claim("email", "u@example.com")
                .claim("provider", "local")
                .claim("emailVerified", true)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(TestJwt.key(), Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> parser.parse(token))
                .isInstanceOf(InvalidJwtException.class);
    }

    @Test
    void acceptsAuthServiceDerivedKey_sameBytes() {
        // Mirror the Auth Service's exact derivation path to prove a token
        // signed by the Auth Service validates against our parser.
        byte[] bytes = Base64.getDecoder().decode(TestJwt.SECRET_BASE64);
        var authServiceKey = Keys.hmacShaKeyFor(bytes);
        UUID id = UUID.randomUUID();
        String token = Jwts.builder()
                .subject(id.toString())
                .claim("email", "u@example.com")
                .claim("provider", "google")
                .claim("emailVerified", true)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(900)))
                .signWith(authServiceKey, Jwts.SIG.HS256)
                .compact();

        AuthenticatedUser user = parser.parse(token);

        assertThat(user.id()).isEqualTo(id);
        assertThat(user.provider()).isEqualTo("google");
    }
}
