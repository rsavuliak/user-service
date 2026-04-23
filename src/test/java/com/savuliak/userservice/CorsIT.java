package com.savuliak.userservice;

import com.savuliak.userservice.support.PostgresContainerBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CorsIT extends PostgresContainerBase {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void preflight_onMe_returnsCorsHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ORIGIN, "http://localhost:5173");
        headers.add(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH");

        ResponseEntity<Void> response = rest.exchange(
                "/api/v1/users/me", HttpMethod.OPTIONS,
                new HttpEntity<>(headers), Void.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getAccessControlAllowOrigin())
                .isEqualTo("http://localhost:5173");
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isTrue();
    }

    @Test
    void preflight_onPublicEndpoint_returnsCorsHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ORIGIN, "http://localhost:5173");
        headers.add(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");

        ResponseEntity<Void> response = rest.exchange(
                "/api/v1/users/{id}/public", HttpMethod.OPTIONS,
                new HttpEntity<>(headers), Void.class, UUID.randomUUID());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getAccessControlAllowOrigin())
                .isEqualTo("http://localhost:5173");
    }

    @Test
    void preflight_onInternalEndpoint_hasNoCorsHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ORIGIN, "http://localhost:5173");
        headers.add(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");

        ResponseEntity<Void> response = rest.exchange(
                "/api/v1/users/internal/create", HttpMethod.OPTIONS,
                new HttpEntity<>(headers), Void.class);

        // Spring returns no CORS headers because the path isn't mapped.
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
        // And without CORS, the internal API key filter rejects unauthenticated preflight.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
