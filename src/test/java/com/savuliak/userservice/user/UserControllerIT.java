package com.savuliak.userservice.user;

import com.savuliak.userservice.support.PostgresContainerBase;
import com.savuliak.userservice.support.TestJwt;
import org.junit.jupiter.api.BeforeEach;
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

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UserControllerIT extends PostgresContainerBase {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void clean() {
        userRepository.deleteAll();
    }

    @Test
    void getMe_validToken_returns200WithProfile() {
        UUID id = seedUser("Jane", "https://example.com/a.png", Map.of("theme", "dark"), "9.99");
        String token = TestJwt.valid(id, "jane@example.com", "local", true);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/users/me", HttpMethod.GET,
                new HttpEntity<>(cookieHeaders(token)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("id")).isEqualTo(id.toString());
        assertThat(body.get("displayName")).isEqualTo("Jane");
        assertThat(body.get("avatarUrl")).isEqualTo("https://example.com/a.png");
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = (Map<String, Object>) body.get("settings");
        assertThat(settings).containsEntry("theme", "dark");
        assertThat(body.get("emailVerified")).isEqualTo(true);
        assertThat(new BigDecimal(body.get("balance").toString())).isEqualByComparingTo("9.99");
    }

    @Test
    void getMe_noCookie_returns401() {
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/users/me", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "Unauthorized");
    }

    @Test
    void getMe_expiredToken_returns401() {
        UUID id = UUID.randomUUID();
        String token = TestJwt.expired(id, "u@example.com");

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/users/me", HttpMethod.GET,
                new HttpEntity<>(cookieHeaders(token)), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getMe_emailVerifiedFromJwt_notFromDb() {
        UUID id = seedUser("Jane", null, Map.of(), "0");
        String token = TestJwt.valid(id, "jane@example.com", "local", false);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/users/me", HttpMethod.GET,
                new HttpEntity<>(cookieHeaders(token)), Map.class);

        assertThat(response.getBody().get("emailVerified")).isEqualTo(false);
    }

    @Test
    void patchMe_onlyDisplayName_leavesOtherFieldsUntouched() {
        UUID id = seedUser("Old", "https://example.com/a.png", Map.of("theme", "dark"), "0");
        String token = TestJwt.valid(id, "u@example.com", "local", true);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/users/me", HttpMethod.PATCH,
                new HttpEntity<>("{\"displayName\":\"New\"}", jsonCookieHeaders(token)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        User reloaded = userRepository.findById(id).orElseThrow();
        assertThat(reloaded.getDisplayName()).isEqualTo("New");
        assertThat(reloaded.getAvatarUrl()).isEqualTo("https://example.com/a.png");
        assertThat(reloaded.getSettings()).containsEntry("theme", "dark");
    }

    @Test
    void patchMe_displayNameExplicitNull_clearsField() {
        UUID id = seedUser("Jane", null, Map.of(), "0");
        String token = TestJwt.valid(id, "u@example.com", "local", true);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/users/me", HttpMethod.PATCH,
                new HttpEntity<>("{\"displayName\":null}", jsonCookieHeaders(token)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(userRepository.findById(id).orElseThrow().getDisplayName()).isNull();
    }

    @Test
    void patchMe_settingsMerge_preservesExistingKeys() {
        UUID id = seedUser(null, null, Map.of("theme", "dark", "lang", "en"), "0");
        String token = TestJwt.valid(id, "u@example.com", "local", true);

        rest.exchange("/api/v1/users/me", HttpMethod.PATCH,
                new HttpEntity<>("{\"settings\":{\"theme\":\"light\"}}", jsonCookieHeaders(token)),
                Map.class);

        Map<String, Object> settings = userRepository.findById(id).orElseThrow().getSettings();
        assertThat(settings).containsEntry("theme", "light").containsEntry("lang", "en");
    }

    @Test
    void patchMe_settingsExplicitNull_returns400() {
        UUID id = seedUser(null, null, Map.of("theme", "dark"), "0");
        String token = TestJwt.valid(id, "u@example.com", "local", true);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/users/me", HttpMethod.PATCH,
                new HttpEntity<>("{\"settings\":null}", jsonCookieHeaders(token)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("errors").toString()).contains("settings");
    }

    @Test
    void patchMe_avatarUrlTooLong_returns400() {
        UUID id = seedUser(null, null, Map.of(), "0");
        String token = TestJwt.valid(id, "u@example.com", "local", true);
        String longUrl = "https://example.com/" + "a".repeat(600);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/users/me", HttpMethod.PATCH,
                new HttpEntity<>("{\"avatarUrl\":\"" + longUrl + "\"}", jsonCookieHeaders(token)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("errors").toString()).contains("avatarUrl");
    }

    @Test
    void patchMe_avatarUrlMalformed_returns400() {
        UUID id = seedUser(null, null, Map.of(), "0");
        String token = TestJwt.valid(id, "u@example.com", "local", true);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/users/me", HttpMethod.PATCH,
                new HttpEntity<>("{\"avatarUrl\":\"not a url\"}", jsonCookieHeaders(token)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void patchMe_displayNameTooLong_returns400() {
        UUID id = seedUser(null, null, Map.of(), "0");
        String token = TestJwt.valid(id, "u@example.com", "local", true);
        String longName = "a".repeat(101);

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/users/me", HttpMethod.PATCH,
                new HttpEntity<>("{\"displayName\":\"" + longName + "\"}", jsonCookieHeaders(token)),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getPublic_existing_returnsOnlyPublicFields() {
        UUID id = seedUser("Jane", "https://example.com/a.png", Map.of("secret", "x"), "99.00");

        ResponseEntity<Map> response = rest.getForEntity("/api/v1/users/{id}/public", Map.class, id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKeys("id", "displayName", "avatarUrl");
        assertThat(response.getBody()).doesNotContainKeys("settings", "balance", "emailVerified");
    }

    @Test
    void getPublic_missing_returns404() {
        ResponseEntity<Map> response = rest.getForEntity(
                "/api/v1/users/{id}/public", Map.class, UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getPublic_noAuthRequired() {
        UUID id = seedUser("Jane", null, Map.of(), "0");

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/users/{id}/public", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class, id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private UUID seedUser(String displayName, String avatarUrl,
                          Map<String, Object> settings, String balance) {
        UUID id = UUID.randomUUID();
        userRepository.save(User.builder()
                .id(id)
                .displayName(displayName)
                .avatarUrl(avatarUrl)
                .settings(new java.util.HashMap<>(settings))
                .balance(new BigDecimal(balance))
                .build());
        return id;
    }

    private HttpHeaders cookieHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "token=" + token);
        return headers;
    }

    private HttpHeaders jsonCookieHeaders(String token) {
        HttpHeaders headers = cookieHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
