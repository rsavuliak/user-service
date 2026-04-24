# User Service — Implementation Plan

Detailed, phased plan for building the User Service microservice (Java 21 + Spring Boot 3.x + PostgreSQL). Companion to the existing Auth Service at `https://auth.savuliak.com/api/v1/auth`. Trusts JWTs issued by the Auth Service; does not handle authentication itself.

> **Revision note (r2):** this plan incorporates two rounds of staff-eng review against the Auth Service codebase. Round 1 fixes (Base64 JWT secret, idempotent internal create, settings NOT-NULL handling) and round 2 fixes (TOCTOU on upsert, JsonNullable validation, correct Auth Service call sites, transactional registration, `JdkClientHttpRequestFactory`, `@RestClientTest`) are all in scope.

---

## 0. Scope Summary

**Build (User Service)**
- REST service that owns user profile data (display name, avatar URL, JSONB settings, balance).
- Internal endpoints (API-key protected, **idempotent**) for Auth Service → User Service calls (create/delete profile).
- Public endpoints (JWT-cookie protected) for the frontend (`GET /me`, `PATCH /me`, `GET /{id}/public`).
- Flyway migrations, Testcontainers integration tests, Docker Compose for local Postgres.

**Build (Auth Service companion changes — new scope from review)**
- `RestClient` bean + `INTERNAL_API_KEY` config.
- Call `POST /api/v1/users/internal/create` after the user row is persisted in `AuthService.register()` and in the Google OAuth flow.
- Call `DELETE /api/v1/users/internal/{id}` in `AuthService.deleteAccount()`.
- Explicit failure semantics (see §6.5).

**Do NOT build**
- Authentication/login/registration endpoints in User Service.
- Spring Security dependency (use a plain `HandlerInterceptor` + servlet filter).
- File upload / email sending / rate limiting / caching.
- API versioning beyond `/api/v1/`.

---

## 1. Project Bootstrap

### 1.1 Generate Maven project
- Spring Boot 3.x (latest stable), Java 21, packaging `jar`.
- Group: `com.savuliak`, artifact: `user-service`, name: `user-service`.
- Base package: `com.savuliak.userservice`.

### 1.2 Dependencies (`pom.xml`)
- `spring-boot-starter-web`
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-validation`
- `org.postgresql:postgresql` (runtime)
- `org.flywaydb:flyway-core`
- `org.flywaydb:flyway-database-postgresql`
- `io.jsonwebtoken:jjwt-api`, `jjwt-impl` (runtime), `jjwt-jackson` (runtime) — 0.12.x (same family as Auth Service)
- `org.openapitools:jackson-databind-nullable` — for `JsonNullable<T>` PATCH semantics
- `org.projectlombok:lombok` (provided)
- `spring-boot-starter-test` (test)
- `org.testcontainers:junit-jupiter` (test)
- `org.testcontainers:postgresql` (test)

Explicitly **exclude** `spring-boot-starter-security`.

### 1.3 Repo scaffolding
- `.gitignore` (Maven, IntelliJ, macOS, `target/`, `.env`, `logs/`).
- `README.md` replaced with developer instructions: prerequisites, run Postgres via compose, env vars, `./mvnw spring-boot:run`, run tests, PATCH null-vs-absent semantics.
- `.mvn/` wrapper and `mvnw` / `mvnw.cmd` scripts.

---

## 2. Configuration & Environment

### 2.1 `application.yml` (main)
```yaml
server:
  port: 8081

spring:
  datasource:
    url: jdbc:postgresql://localhost:5433/user_service
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
  flyway:
    enabled: true
    locations: classpath:db/migration

jwt:
  secret: ${JWT_SECRET}     # Base64-encoded; MUST be identical to Auth Service's JWT_SECRET

cors:
  allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:5173}

internal:
  api-key: ${INTERNAL_API_KEY}    # Shared with Auth Service; min 32 chars

app:
  cookie-name: token
```

### 2.2 `application-test.yml`
- Overrides datasource to be wired at runtime by Testcontainers via `@DynamicPropertySource`.
- Fixed `jwt.secret` (Base64-encoded, decoded to ≥ 32 bytes for HS256) and `internal.api-key` (≥ 32 chars) for tests.

### 2.3 Environment variables (documented in README)
- `DB_USERNAME`, `DB_PASSWORD`
- `JWT_SECRET` — **Base64-encoded** HMAC secret. Must match the Auth Service exactly (the Auth Service calls `Base64.getDecoder().decode(jwtSecret)` before `Keys.hmacShaKeyFor(...)`; the User Service must do the same).
- `INTERNAL_API_KEY` — shared with Auth Service; min 32 chars.
- `CORS_ALLOWED_ORIGINS` — comma-separated list.

### 2.4 `@ConfigurationProperties` classes
- `JwtProperties` → `jwt.secret` (Base64 string).
- `AppProperties` → `app.cookie-name`.
- `InternalApiProperties` → `internal.api-key`.
- `CorsProperties` → `cors.allowed-origins` (list).
- Registered via `@EnableConfigurationProperties` on a config class.

### 2.5 Startup validation (fail-fast)
Implemented as an `ApplicationRunner` or `@PostConstruct` on the properties holder:
- `JWT_SECRET` — Base64-decode must succeed **and** produce ≥ 32 bytes (HS256 minimum). Fail with a clear message if not.
- `INTERNAL_API_KEY` — must be non-blank and ≥ 32 characters.
- `CORS_ALLOWED_ORIGINS` — must contain at least one entry.

---

## 3. Database & Migrations

### 3.1 Flyway migration `V1__create_users_table.sql`
```sql
CREATE TABLE users (
    id UUID PRIMARY KEY,
    display_name VARCHAR(100),
    avatar_url VARCHAR(500),
    settings JSONB NOT NULL DEFAULT '{}',
    balance DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
```
- `id` is supplied by the Auth Service — **not** auto-generated.
- `settings` is `NOT NULL`; both entity default and service layer must guarantee a non-null value.
- Migrations are immutable; any future change is a new `V<n>__*.sql` file.

### 3.2 JPA entity `User`
- `@Entity @Table(name = "users")`.
- `id` — `@Id`, type `UUID`, **no** `@GeneratedValue`.
- `displayName`, `avatarUrl` — `String`, nullable.
- `settings` — `Map<String, Object>`:
  ```java
  @Builder.Default
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "settings", columnDefinition = "jsonb", nullable = false)
  private Map<String, Object> settings = new HashMap<>();
  ```
  `@Builder.Default` prevents Lombok from overwriting the default with `null` when the builder path is used; `columnDefinition = "jsonb"` ensures Hibernate keeps the column as JSONB (not `text`).
- `balance` — `BigDecimal`, precision 12 scale 2, `NOT NULL`, default `BigDecimal.ZERO` via `@Builder.Default`.
- `createdAt` — `OffsetDateTime`, `updatable = false`, set via `@PrePersist`.
- `updatedAt` — `OffsetDateTime`, set via `@PrePersist` and `@PreUpdate`.
- Lombok: `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`.

### 3.3 Repository
- `UserRepository extends JpaRepository<User, UUID>`.
- Default methods cover `findById`, `existsById`, `deleteById`, `save`.

---

## 4. Security (JWT + internal API key, no Spring Security)

### 4.1 `JwtTokenParser` (`security/`)
- Key derivation — **must match the Auth Service exactly**:
  ```java
  byte[] keyBytes = Base64.getDecoder().decode(jwtProperties.secret());
  SecretKey key = Keys.hmacShaKeyFor(keyBytes);
  ```
- `AuthenticatedUser parse(String token)`:
  - Validates signature, `exp`, `iat`.
  - Reads `sub` (UUID), `email`, `emailVerified` (boolean), `provider` (string).
  - Throws `InvalidJwtException` on malformed/expired/invalid signature/missing required claims.

### 4.2 `AuthenticatedUser` (record)
```java
public record AuthenticatedUser(UUID id, String email, boolean emailVerified, String provider) {}
```

### 4.3 `JwtAuthInterceptor` (implements `HandlerInterceptor`)
- `preHandle` has **no** path-skip logic. It trusts `WebConfig` to be the single source of truth for which paths it runs on.
  1. Read the cookie named via `app.cookie-name` (default `token`).
  2. If absent → write `401 { "error": "Unauthorized" }` and return false.
  3. Parse via `JwtTokenParser`. On exception → same 401.
  4. Store `AuthenticatedUser` as request attribute `AUTHENTICATED_USER`.
- Uses the injected `ObjectMapper` for the JSON error body.

### 4.4 `@CurrentUser` annotation + `CurrentUserArgumentResolver`
- Annotation: `@Target(PARAMETER) @Retention(RUNTIME)`.
- Resolver extracts the `AuthenticatedUser` request attribute and injects it.
- If attribute missing (should not happen because the interceptor populated it), throw `IllegalStateException` → 500.

### 4.5 `InternalApiKeyFilter` (servlet filter in `common/`)
- Registered via `FilterRegistrationBean`; URL pattern `/api/v1/users/internal/*`.
- Reads `X-Internal-Api-Key` header; compares to `internal.api-key` with `MessageDigest.isEqual` (constant-time).
- On mismatch/missing → `401 { "error": "Unauthorized" }` and stop the chain.
- On success → continue; does not populate `AuthenticatedUser`.

### 4.6 `WebConfig` — **single source of truth for interceptor scope**
- Implements `WebMvcConfigurer`.
- Registers `JwtAuthInterceptor` via `addInterceptors` with:
  - `addPathPatterns("/api/v1/users/**")`
  - `excludePathPatterns("/api/v1/users/internal/**", "/api/v1/users/*/public")`
- Registers `CurrentUserArgumentResolver` via `addArgumentResolvers`.
- Adding a new public endpoint means editing **only** this config; the interceptor body does not need to change.

### 4.7 `CorsConfig`
- CORS is for browsers only — scope to user-facing paths. Do **not** apply to `/internal/**`.
- Via `WebMvcConfigurer.addCorsMappings`:
  - `registry.addMapping("/api/v1/users/me").allowedOriginPatterns(...).allowCredentials(true).allowedMethods("GET","PATCH","OPTIONS");`
  - `registry.addMapping("/api/v1/users/*/public").allowedOriginPatterns(...).allowCredentials(true).allowedMethods("GET","OPTIONS");`
- Allowed headers: `Content-Type`.
- Internal paths are intentionally **not** mapped.

---

## 5. API Layer

### 5.1 DTOs (`user/dto/`)

**`CreateUserRequest`** (internal)
- `@NotNull UUID id`, `@Email @NotBlank String email`.

**`UpdateUserRequest`** (PATCH /me)
- `JsonNullable<String> displayName` — absent = don't touch, `null` = clear, value = set.
- `JsonNullable<String> avatarUrl` — absent/null/value same semantics.
- `Map<String, Object> settings` — **plain Map, not `JsonNullable`**. Absent (null reference) = don't touch; present = shallow-merge. The column is `NOT NULL`, so there is no "clear to null" semantic. A client sending `"settings": null` is rejected as 400 at the service layer (see §5.3).

> **No Bean Validation annotations on `JsonNullable<T>` fields.** Hibernate Validator inspects the declared field type; `@URL`/`@Size` applied to `JsonNullable<String>` are silent no-ops (the validator sees the wrapper, not the inner `String`). All size/format checks for these fields live in the service layer after unwrapping (see §5.3). Do **not** decorate `JsonNullable` fields with constraint annotations — they mislead readers into thinking validation happens automatically.

**`UserResponse`** (public, returned from `/me` and `PATCH /me`)
- `id, displayName, avatarUrl, settings, balance, emailVerified, createdAt`.
- `emailVerified` sourced from JWT at response time.
- `balance` — currently no write path. README + a short DTO comment note it is a placeholder for a future billing flow.

**`PublicUserResponse`** — `id, displayName, avatarUrl`.

**`InternalCreateResponse`** (internal only, **separate from `UserResponse`**)
- `id, createdAt`.
- Deliberately omits `emailVerified`, `balance`, `settings` — those are either irrelevant to the Auth caller or misleading when no JWT context exists.

### 5.2 Exceptions (`user/exception/` and `security/`)
- `UserNotFoundException extends RuntimeException`.
- `UserAlreadyExistsException extends RuntimeException` — retained but no longer surfaced for idempotent create (see §5.3).
- `InvalidSettingsException extends RuntimeException` — thrown on explicit `"settings": null`.
- `ValidationException extends RuntimeException` — thrown by service-layer checks on `displayName` / `avatarUrl` (→ 400).
- `InvalidJwtException extends RuntimeException`.

### 5.3 `UserService` (`user/`)
- Constructor-injected `UserRepository`, `ObjectMapper`.
- Methods:
  - `EnsureProfileResult ensureProfile(CreateUserRequest req)` — **idempotent upsert, race-safe**:
    - If `findById(req.id())` returns a user → return `EnsureProfileResult(existing, created=false)`.
    - Else attempt `save(new User(...))` inside a try/catch:
      ```java
      try {
          User saved = userRepository.save(newUser);
          return new EnsureProfileResult(saved, true);
      } catch (DataIntegrityViolationException e) {
          // Lost a race with a concurrent request for the same id (PK violation).
          // The other writer's row is authoritative — return it as "already existed".
          User existing = userRepository.findById(req.id())
              .orElseThrow(() -> e); // PK violation without a row means something else is wrong
          return new EnsureProfileResult(existing, false);
      }
      ```
    - This covers the PostgreSQL `READ_COMMITTED` TOCTOU window where two concurrent Auth Service calls (double-submit or retry on slow response) both see "absent" and race to INSERT.
    - Controller maps `created=true → 201`, `created=false → 200`.
  - `void deleteById(UUID id)`
    - If not present → `UserNotFoundException`.
    - Else `deleteById`.
  - `UserResponse getMe(AuthenticatedUser principal)`
    - Load by `principal.id()` or throw `UserNotFoundException`.
    - Map + overlay `emailVerified` from JWT.
  - `UserResponse updateMe(AuthenticatedUser principal, UpdateUserRequest req)`
    - Load entity; if missing → `UserNotFoundException`.
    - `displayName` — if `JsonNullable.isPresent()`:
      - inner `null` → set to null (clear).
      - inner non-null → trim; reject `length() > 100` with `ValidationException` (400); set.
    - `avatarUrl` — if `JsonNullable.isPresent()`:
      - inner `null` → set to null (clear).
      - inner non-null → reject `length() > 500` or failed URL parse (`new URI(s).toURL()`) with `ValidationException` (400); set.
    - `settings` — if the request field was present in JSON but value is `null` → throw `InvalidSettingsException` (400). If present and non-null → `existing.putAll(requestSettings)` (shallow merge).
    - Save; return response with `emailVerified` from JWT.
  - `PublicUserResponse getPublic(UUID id)`
    - Load or `UserNotFoundException`; map to public DTO.
- Entity→DTO mapping is inline (no MapStruct).

> **Why service-layer validation instead of DTO annotations:** `JsonNullable<String>` hides the inner type from Hibernate Validator, so `@Size`/`@URL` applied to the wrapper are silently ignored. Centralizing the checks here guarantees they actually run, keeps the DTO a pure data carrier, and lets the `isPresent()` branch gate the work. An alternative is a custom `ConstraintValidator<…, JsonNullable<String>>`, but service-layer validation is simpler and co-located with the mutation.

### 5.4 `UserController` (`user/`)
- `@RestController @RequestMapping("/api/v1/users")`.
- `GET /me` → `getMe(@CurrentUser AuthenticatedUser user)` → 200.
- `PATCH /me` → `@Valid @RequestBody UpdateUserRequest`, `@CurrentUser AuthenticatedUser user` → 200 / 400.
- `GET /{id}/public` → 200 / 404.

### 5.5 `InternalUserController` (`user/`)
- `@RestController @RequestMapping("/api/v1/users/internal")`.
- `POST /create` → idempotent upsert:
  - New → `201 Created` with `InternalCreateResponse`.
  - Already exists → `200 OK` with `InternalCreateResponse` of the existing profile. No 409.
- `DELETE /{id}` → `204 No Content` on success, `404` if not found. Use `ResponseEntity.noContent().build()`.
- No `@CurrentUser` — the internal filter already vetted the caller via API key.

### 5.6 `GlobalExceptionHandler` (`common/`)
- `@RestControllerAdvice`.
- Handlers:
  - `MethodArgumentNotValidException` → 400 `{ "errors": ["field: message", ...] }`.
  - `HttpMessageNotReadableException` → 400 `{ "errors": ["body: malformed"] }`.
  - `ValidationException` → 400 `{ "errors": [message] }` (service-layer size/format checks for `displayName` / `avatarUrl`).
  - `InvalidSettingsException` → 400 `{ "errors": ["settings: must not be null"] }`.
  - `UserNotFoundException` → 404 `{ "error": "User not found" }`.
  - `UserAlreadyExistsException` → 409 `{ "error": "User already exists" }` (kept for any future non-idempotent path; unused by current controllers).
  - `InvalidJwtException` → 401 `{ "error": "Unauthorized" }` (fallback; the interceptor usually writes 401 directly).
  - Fallback `Exception` → 500 `{ "error": "Internal server error" }` (log stack trace; don't expose).

---

## 6. Auth Service Companion Changes

The User Service is inert until the Auth Service actually calls it. These changes are part of the same delivery. Call-site names below were verified against the Auth Service source.

### 6.1 Config
- Add env vars `INTERNAL_API_KEY` (same value as User Service) and `USER_SERVICE_BASE_URL` (default `http://localhost:8081`).
- Add `@ConfigurationProperties` to the Auth Service exposing both.
- Add startup validation mirroring §2.5 (`INTERNAL_API_KEY` ≥ 32 chars).

### 6.2 HTTP client (`RestClient` + `JdkClientHttpRequestFactory`)
- Use Spring Framework 6.1+ `RestClient` (already on the classpath — **no** new starter needed).
- `RestClient.builder()` has **no** `.connectTimeout()` method; timeouts live on the underlying `ClientHttpRequestFactory`. Use `JdkClientHttpRequestFactory` (JDK 11+ `java.net.http.HttpClient`) so **no extra dependency** is added to the Auth Service:
  ```java
  HttpClient http = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .build();
  JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
  factory.setReadTimeout(Duration.ofSeconds(5));
  return RestClient.builder()
          .baseUrl(props.baseUrl())
          .defaultHeader("X-Internal-Api-Key", props.apiKey())
          .requestFactory(factory)
          .build();
  ```
- Do **not** add `httpclient5` — `JdkClientHttpRequestFactory` is sufficient.

### 6.3 Client wrapper
- `UserServiceClient` with `ensureProfile(UUID id, String email)` and `deleteProfile(UUID id)`.
- `ensureProfile` treats both `200` and `201` as success (idempotent); any 5xx / network failure bubbles up as an exception so the caller's `@Transactional` boundary rolls back (see §6.5).
- `deleteProfile` treats `204` as success; treats `404` as success (already gone — the effect the caller wanted); 5xx logged and swallowed (see §6.5).

### 6.4 Call sites (verified against the Auth Service code)
- **Register — `AuthService.register()`:** add `@Transactional` to the method (currently not annotated; `userRepository.save(...)` auto-commits immediately, which is exactly what creates the zombie-user risk). Inside the transaction, call `userServiceClient.ensureProfile(user.getId(), user.getEmail())` **after** the user row has been saved but **before** the method returns. On failure the exception propagates and Spring rolls back the whole transaction — no orphan auth row. Audit `emailVerificationService.generateToken()` and `emailService.sendVerificationEmail()` to decide whether they run inside the transaction or are deferred to after commit (typically email sending should be **after commit** to avoid sending a verification email for a rolled-back registration — use `TransactionSynchronizationManager.registerSynchronization` or `@TransactionalEventListener(AFTER_COMMIT)`).
- **Google OAuth — `GoogleAuthService.findOrCreateUser()`:** the method uses `userService.findUserByEmail(email).map(existing -> existing).orElseGet(() -> userService.createUser(email, "google"))`. Call `ensureProfile` **only inside the `orElseGet` lambda** (new-user branch). Placing it after `processOAuthCallback()` returns would fire on every repeat Google login, which is wasteful and breaks the "create profile on first OAuth login" contract. Same transactional requirement applies — wrap the new-user branch (or the whole `findOrCreateUser`) in `@Transactional` so a User Service failure rolls back the freshly-created auth row.
- **Delete — `AuthController.deleteAccount()` (NOT `AuthService.deleteAccount()`, which does not exist):** the current flow is
  ```
  AuthController.deleteAccount()
    └── userService.deleteById(principal.id())   // @Transactional in UserService
  ```
  Call `userServiceClient.deleteProfile(principal.id())` in the **controller**, **after** `userService.deleteById(...)` has returned (i.e. after the local transaction has committed). Do **not** put this call inside `UserService.deleteById`: that method is `@Transactional`, so a 5xx from User Service would throw, roll back the local delete, and leave the auth row intact — the opposite of the stated "log and continue" semantics.

### 6.5 Failure semantics (explicit, verified against call sites)
- **Registration / Google OAuth first-login (ensureProfile):** if the User Service is unreachable or returns 5xx, **fail the whole flow** with a 502. This is only safe because §6.4 puts the call inside an `@Transactional` method — the auth user row is never committed, so a retry is clean. Rationale: simpler invariants (no zombie auth rows without a profile); registrations are low-frequency enough that brief outages are acceptable.
- **Delete (deleteProfile):** runs in `AuthController` **after** the local auth delete has committed. If the User Service call fails, log at ERROR and continue — the auth account is gone, which is what the user asked for. A periodic reconciliation job can sweep orphan profiles later. The asymmetry (register is transactional, delete is fire-and-forget) is deliberate and documented in the Auth Service README.

### 6.6 Tests (Auth Service side)
- Use `@RestClientTest(UserServiceClient.class)` for the client wrapper — this is the Spring Boot 3.2+ analogue of `@RestTemplateTest`; `MockRestServiceServer` does **not** work with `RestClient` directly. `@RestClientTest` auto-configures a `MockServer`-style binding against the `RestClient.Builder` autoconfig and covers 200/201/204/404/5xx cases.
- Service-level tests for `AuthService.register()` and `GoogleAuthService.findOrCreateUser()` use `@MockBean UserServiceClient` — simpler than standing up a mock HTTP server, and lets us assert on method calls (e.g. "ensureProfile called exactly once with this id/email on first Google login, zero times on subsequent logins").
- Assertion on rollback: a test that makes `UserServiceClient.ensureProfile` throw on register must verify the auth user row is **not** present in the DB afterward.
- `AuthController.deleteAccount()` test: when `UserServiceClient.deleteProfile` throws, the controller still returns 200 and the auth user row is still gone.

---

## 7. Docker Compose (local dev)

`docker-compose.yml` at User Service project root:
```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: user_service
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5433:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data

volumes:
  pgdata:
```
- Port `5433` chosen to avoid collision with the Auth Service's local Postgres on `5432`.

---

## 8. Testing Strategy

### 8.1 Unit tests
- `JwtTokenParserTest`:
  - Valid token **signed with the Base64-decoded secret** → returns `AuthenticatedUser` with correct fields.
  - Key-derivation contract test: a token produced by the Auth Service's exact derivation path validates here (fixture-based).
  - Expired / malformed / wrong signature / missing claims → `InvalidJwtException`.
- `UserServiceTest` (Mockito):
  - `ensureProfile` new user → creates + returns `created=true`.
  - `ensureProfile` existing user → no write, returns `created=false`.
  - `ensureProfile` race — `save` throws `DataIntegrityViolationException`, subsequent `findById` returns the winner → returns `created=false` with the winner's row (no 5xx).
  - `updateMe` settings merge preserves untouched keys.
  - `updateMe` with explicit `settings: null` → `InvalidSettingsException`.
  - `updateMe` with `displayName` > 100 chars → `ValidationException`.
  - `updateMe` with `avatarUrl` > 500 chars or unparseable → `ValidationException`.
  - `updateMe` with `displayName: null` (explicit) → clears the field.
  - `updateMe` on missing user → `UserNotFoundException`.
  - `deleteById` on missing user → `UserNotFoundException`.

### 8.2 Integration tests (Testcontainers + `@SpringBootTest` + `@AutoConfigureMockMvc`)
- `abstract class PostgresContainerBase` starts `PostgreSQLContainer<?>`; `@DynamicPropertySource` wires `spring.datasource.*`. Declared `abstract` so Surefire does not attempt to instantiate it.
- Helper `TestJwt` builds signed tokens using the test `jwt.secret` (Base64-encoded fixture).

**`UserControllerIT`**
- `GET /me` with valid JWT cookie → 200, body matches seeded user, `emailVerified` comes from the JWT.
- `GET /me` without cookie → 401 with `{ "error": "Unauthorized" }`.
- `GET /me` with invalid / expired cookie → 401.
- `PATCH /me` updating only `displayName` → other fields unchanged.
- `PATCH /me` settings merge → existing keys preserved; specified key overwritten.
- `PATCH /me` with `settings: null` → 400.
- `PATCH /me` with `avatarUrl` too long → 400 with errors list (service-layer check; confirms the size validation actually runs even though there is no `@Size` annotation on the DTO field).
- `PATCH /me` with `displayName: null` (explicit JSON null) → 200, field cleared in DB.
- `GET /{id}/public` → 200 with only public fields; no `balance`, no `settings`.
- `GET /{id}/public` for unknown id → 404.

**`InternalUserControllerIT`**
- `POST /internal/create` with valid API key + new id → 201 + `InternalCreateResponse`.
- `POST /internal/create` with valid API key + existing id → **200** + existing `InternalCreateResponse` (idempotent; no 409).
- `POST /internal/create` concurrent — two requests with the same id fired in parallel → one 201, one 200, no 500. (Race regression test for §5.3.)
- `POST /internal/create` without/with-wrong API key → 401.
- `DELETE /internal/{id}` with valid key → **204** no body.
- `DELETE /internal/{id}` on missing id → 404.

**`CorsIT`**
- `OPTIONS /api/v1/users/me` with `Origin` → response includes `Access-Control-Allow-Credentials: true` and configured origin.
- `OPTIONS /api/v1/users/internal/create` with `Origin` → **no** CORS headers (internal paths are not CORS-mapped).

### 8.3 Test layout
- `src/test/java/com/savuliak/userservice/` mirrors main packages.
- `src/test/resources/application-test.yml` + Testcontainers-run migrations.

---

## 9. Project Structure (final layout)

```
user-service/
├── docker-compose.yml
├── pom.xml
├── mvnw, mvnw.cmd, .mvn/
├── README.md
├── PLAN.md
└── src/
    ├── main/
    │   ├── java/com/savuliak/userservice/
    │   │   ├── UserServiceApplication.java
    │   │   ├── config/
    │   │   │   ├── AppProperties.java
    │   │   │   ├── CorsConfig.java
    │   │   │   ├── CorsProperties.java
    │   │   │   ├── JwtProperties.java
    │   │   │   ├── InternalApiProperties.java
    │   │   │   ├── StartupValidator.java
    │   │   │   └── WebConfig.java
    │   │   ├── security/
    │   │   │   ├── AuthenticatedUser.java
    │   │   │   ├── CurrentUser.java
    │   │   │   ├── CurrentUserArgumentResolver.java
    │   │   │   ├── InvalidJwtException.java
    │   │   │   ├── JwtAuthInterceptor.java
    │   │   │   └── JwtTokenParser.java
    │   │   ├── user/
    │   │   │   ├── InternalUserController.java
    │   │   │   ├── User.java
    │   │   │   ├── UserController.java
    │   │   │   ├── UserRepository.java
    │   │   │   ├── UserService.java
    │   │   │   ├── dto/
    │   │   │   │   ├── CreateUserRequest.java
    │   │   │   │   ├── InternalCreateResponse.java
    │   │   │   │   ├── PublicUserResponse.java
    │   │   │   │   ├── UpdateUserRequest.java
    │   │   │   │   └── UserResponse.java
    │   │   │   └── exception/
    │   │   │       ├── InvalidSettingsException.java
    │   │   │       ├── UserAlreadyExistsException.java
    │   │   │       ├── UserNotFoundException.java
    │   │   │       └── ValidationException.java
    │   │   └── common/
    │   │       ├── GlobalExceptionHandler.java
    │   │       └── InternalApiKeyFilter.java
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    │           └── V1__create_users_table.sql
    └── test/
        ├── java/com/savuliak/userservice/
        │   ├── security/JwtTokenParserTest.java
        │   ├── user/UserServiceTest.java
        │   ├── user/UserControllerIT.java
        │   ├── user/InternalUserControllerIT.java
        │   ├── CorsIT.java
        │   └── support/
        │       ├── PostgresContainerBase.java      (abstract)
        │       └── TestJwt.java
        └── resources/
            └── application-test.yml
```

---

## 10. Phased Build Order

Work in merge-sized slices; each phase should end green (compiles + tests pass).

1. **Bootstrap** — `pom.xml`, application class, `application.yml`, `.gitignore`, Docker Compose, startup validator. `mvn verify` green.
2. **DB layer** — `User` entity with `@Builder.Default` settings and `columnDefinition="jsonb"`, `UserRepository`, `V1__create_users_table.sql`, `@DataJpaTest` Testcontainers test verifying insert + JSONB round-trip + NOT-NULL default honored.
3. **Internal API (idempotent, race-safe)** — `InternalApiKeyFilter`, `InternalUserController`, `UserService.ensureProfile/delete` with `DataIntegrityViolationException` catch, `InternalCreateResponse`. Integration tests cover new (201), existing (200), concurrent race (one 201 + one 200, no 500), bad key (401), delete success (204), delete missing (404).
4. **JWT plumbing** — `JwtProperties`, `JwtTokenParser` with **Base64-decoded** key, `AuthenticatedUser`, `JwtAuthInterceptor` (no path logic), `@CurrentUser` + resolver, `WebConfig`.
5. **Public API** — `UserController`, `UpdateUserRequest` (JsonNullable for strings with **no** Bean Validation annotations, plain Map for settings), service-layer size/URL validation, `UserService.getMe/updateMe/getPublic`. Integration tests including `settings: null` → 400, long `avatarUrl` → 400, explicit `displayName: null` → clears.
6. **CORS & polish** — `CorsConfig` scoped to user-facing paths, CORS integration test, README, `.env.example`.
7. **Auth Service companion** — `RestClient` with `JdkClientHttpRequestFactory`, `UserServiceClient`, `@Transactional` `register()` + new-user branch of `GoogleAuthService.findOrCreateUser()`, `deleteProfile` in `AuthController.deleteAccount()` after commit, email-sending moved to `AFTER_COMMIT`, failure semantics per §6.5, `@RestClientTest` + `@MockBean` tests including rollback assertion.
8. **Harden** — error envelope audit, log levels, startup validation messages.

---

## 11. Open Decisions

- **JWT algorithm:** closed — **HS256 with Base64-decoded secret** to match the Auth Service.
- **PATCH null semantics:** closed — `JsonNullable` for `displayName`/`avatarUrl` (validated in service layer because Bean Validation does not traverse the wrapper), plain `Map` for `settings` (explicit null = 400).
- **Internal create contract:** closed — **idempotent, race-safe upsert** (201 new / 200 existing; `DataIntegrityViolationException` → re-fetch and return 200), distinct `InternalCreateResponse` DTO.
- **Settings merge depth:** shallow (top-level key overwrite). Revisit if nested merge is requested.
- **Balance in `UserResponse`:** kept with a README note and DTO comment that it is a placeholder until a billing/ledger flow exists.
- **Delete ordering in Auth Service:** `deleteProfile` runs in `AuthController.deleteAccount()` (verified — `AuthService.deleteAccount()` does not exist) **after** `userService.deleteById(...)` has committed; failures are logged and swept by a future reconciler.
- **Registration failure mode:** closed — `AuthService.register()` and the new-user branch of `GoogleAuthService.findOrCreateUser()` become `@Transactional`; if User Service is down the exception rolls back the auth row and returns 502. No zombie auth users.
- **Email sending on registration:** move to `@TransactionalEventListener(AFTER_COMMIT)` so a rolled-back registration does not trigger a verification email to a non-existent account.
- **HTTP client factory:** `JdkClientHttpRequestFactory` (JDK 11+ `HttpClient`) — no new dependency on `httpclient5`.

---

## 12. Acceptance Checklist

- [ ] `./mvnw verify` passes locally (unit + integration).
- [ ] Startup fails fast with a clear error when `JWT_SECRET` is not valid Base64 or decodes to < 32 bytes.
- [ ] Startup fails fast when `INTERNAL_API_KEY` is blank or < 32 chars.
- [ ] A token signed by the Auth Service's exact key derivation validates in this service (fixture test).
- [ ] `docker compose up -d postgres && ./mvnw spring-boot:run` serves `GET /api/v1/users/me` with a test cookie.
- [ ] Auth Service `RestClient` calls `POST /internal/create` on register and **only on new-user branch** of Google OAuth; retries / double-submits return 200 (not 409, not 500).
- [ ] Concurrent `POST /internal/create` for the same id under parallel load yields exactly one 201 and one 200 — never 500.
- [ ] Failing `UserServiceClient.ensureProfile` during register **rolls back** the auth user row (no zombie).
- [ ] Auth Service calls `DELETE /internal/{id}` from `AuthController.deleteAccount()` **after** local commit; a User Service 5xx does not resurrect the deleted auth row.
- [ ] `PATCH /me` with `{ "settings": { "theme": "light" } }` preserves other existing settings keys.
- [ ] `PATCH /me` with `{ "settings": null }` returns 400 with the expected error envelope.
- [ ] `PATCH /me` with an over-size `avatarUrl` returns 400 (proves service-layer validation runs despite no DTO annotation).
- [ ] `PATCH /me` with `{ "displayName": null }` clears the field.
- [ ] `DELETE /internal/{id}` returns 204 with no body.
- [ ] All error responses match the documented envelope (`errors` array for 400, `error` string otherwise).
- [ ] No `spring-boot-starter-security` in the dependency tree (`./mvnw dependency:tree | grep security` returns nothing).
- [ ] No `httpclient5` added to the Auth Service's `pom.xml` (`JdkClientHttpRequestFactory` used instead).
- [ ] CORS preflight to `/api/v1/users/internal/*` returns no CORS headers; preflight to `/api/v1/users/me` does.
- [ ] README documents env vars, Base64 JWT secret requirement, local dev flow, and the null-vs-absent PATCH semantics (including why `settings` diverges and why DTO-level annotations are absent on `JsonNullable` fields).
