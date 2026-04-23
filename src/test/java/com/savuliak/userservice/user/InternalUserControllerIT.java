package com.savuliak.userservice.user;

import com.savuliak.userservice.support.PostgresContainerBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InternalUserControllerIT extends PostgresContainerBase {

    private static final String API_KEY = "test-internal-api-key-48-chars-exactly-for-tests!";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository userRepository;

    @Test
    void create_new_returns201() {
        UUID id = UUID.randomUUID();

        ResponseEntity<Map> response = postCreate(id, "u@example.com", API_KEY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsKey("id").containsKey("createdAt");
        assertThat(userRepository.existsById(id)).isTrue();
    }

    @Test
    void create_existing_returns200_idempotent() {
        UUID id = UUID.randomUUID();
        postCreate(id, "u@example.com", API_KEY);

        ResponseEntity<Map> second = postCreate(id, "u@example.com", API_KEY);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).containsKey("id").containsKey("createdAt");
    }

    @Test
    void create_concurrent_sameId_yieldsOne201AndOne200_noServerError() throws Exception {
        UUID id = UUID.randomUUID();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<ResponseEntity<Map>> task = () -> postCreate(id, "u@example.com", API_KEY);
            Future<ResponseEntity<Map>> f1 = pool.submit(task);
            Future<ResponseEntity<Map>> f2 = pool.submit(task);

            List<HttpStatus> statuses = List.of(
                    (HttpStatus) f1.get().getStatusCode(),
                    (HttpStatus) f2.get().getStatusCode());

            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.OK);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void create_missingApiKey_returns401() {
        UUID id = UUID.randomUUID();

        ResponseEntity<Map> response = postCreate(id, "u@example.com", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "Unauthorized");
    }

    @Test
    void create_wrongApiKey_returns401() {
        UUID id = UUID.randomUUID();

        ResponseEntity<Map> response = postCreate(id, "u@example.com", "wrong-key");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void delete_existing_returns204() {
        UUID id = UUID.randomUUID();
        postCreate(id, "u@example.com", API_KEY);

        ResponseEntity<Void> response = deleteInternal(id, API_KEY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(userRepository.existsById(id)).isFalse();
    }

    @Test
    void delete_missing_returns404() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/users/internal/{id}",
                HttpMethod.DELETE,
                new HttpEntity<>(apiKeyHeaders(API_KEY)),
                Map.class,
                UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "User not found");
    }

    @Test
    void delete_missingApiKey_returns401() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/users/internal/{id}",
                HttpMethod.DELETE,
                new HttpEntity<>(new HttpHeaders()),
                Map.class,
                UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<Map> postCreate(UUID id, String email, String apiKey) {
        HttpHeaders headers = apiKeyHeaders(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"id\":\"" + id + "\",\"email\":\"" + email + "\"}";
        return rest.postForEntity("/api/v1/users/internal/create",
                new HttpEntity<>(body, headers),
                Map.class);
    }

    private ResponseEntity<Void> deleteInternal(UUID id, String apiKey) {
        return rest.exchange("/api/v1/users/internal/{id}",
                HttpMethod.DELETE,
                new HttpEntity<>(apiKeyHeaders(apiKey)),
                Void.class,
                id);
    }

    private HttpHeaders apiKeyHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        if (apiKey != null) {
            headers.add("X-Internal-Api-Key", apiKey);
        }
        return headers;
    }
}
