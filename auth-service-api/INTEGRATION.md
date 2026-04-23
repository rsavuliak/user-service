# Auth Service — User Service Integration Change List

This document enumerates every change the Auth Service team must make so that the Auth Service wires itself up to the new User Service (`user-service/`). It is the authoritative companion to `PLAN.md` §6 in the `user-service` repo.

> **TL;DR** — the User Service is inert until the Auth Service calls it. Deploy `user-service` first; then ship these changes; then turn on the call-sites.

---

## 0. Coordination & Deploy Order

1. Deploy `user-service` to the target environment (image built from `user-service/Dockerfile`) with the shared `JWT_SECRET` and a freshly-generated `INTERNAL_API_KEY`.
2. Verify `GET /actuator/health/readiness` is UP and `POST /api/v1/users/internal/create` accepts a hand-crafted call with the key.
3. Merge the Auth Service changes below behind a feature flag *or* merge unflagged and deploy in the same release window as step 2.
4. After deploy, run a smoke registration and confirm a row appears in both `auth_service.users` and `user_service.users` with the same UUID.

**Rollback:** if the integration call-sites misbehave, either revert the Auth Service deploy or disable the call via a feature flag (recommended — see §8). The User Service itself can stay running; it is a no-op for traffic that does not call it.

---

## 1. Environment Variables (new)

Add to Auth Service config (`.env`, deployment secrets, CI, etc.):

| Name | Required | Rules | Notes |
|------|----------|-------|-------|
| `INTERNAL_API_KEY` | yes | ≥ 32 chars, identical to the User Service's value | Startup must fail-fast on missing/short. Mirror the validation already in `user-service/StartupValidator`. |
| `USER_SERVICE_BASE_URL` | yes | default `http://localhost:8081` | Production: the internal DNS name of the User Service (e.g. `http://user-service.internal:8081`). |

**`JWT_SECRET` must be byte-identical** between the two services. It is Base64-encoded; both services `Base64.getDecoder().decode(...)` then `Keys.hmacShaKeyFor(...)`. A mismatch here is the single most likely integration bug — re-confirm on every deploy.

---

## 2. Code — New Files

### 2.1 `InternalApiProperties` (record + `@ConfigurationProperties`)

```java
@ConfigurationProperties(prefix = "internal")
public record InternalApiProperties(String apiKey) {}
```

### 2.2 `UserServiceProperties`

```java
@ConfigurationProperties(prefix = "user-service")
public record UserServiceProperties(String baseUrl) {}
```

Wire both via `@EnableConfigurationProperties` on an existing config class.

### 2.3 `application.yml` additions

```yaml
internal:
  api-key: ${INTERNAL_API_KEY}

user-service:
  base-url: ${USER_SERVICE_BASE_URL:http://localhost:8081}
```

### 2.4 `UserServiceClientConfig`

```java
@Bean
public RestClient userServiceRestClient(UserServiceProperties userSvc,
                                        InternalApiProperties internal) {
    HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(Duration.ofSeconds(5));
    return RestClient.builder()
            .baseUrl(userSvc.baseUrl())
            .defaultHeader("X-Internal-Api-Key", internal.apiKey())
            .requestFactory(factory)
            .build();
}
```

**Do NOT add `org.apache.httpcomponents.client5:httpclient5`** — `JdkClientHttpRequestFactory` (JDK 11+ `java.net.http.HttpClient`) is sufficient and ships with Java 21. This keeps the dependency tree clean.

### 2.5 `UserServiceClient`

```java
public void ensureProfile(UUID id, String email) {
    // POST /api/v1/users/internal/create
    // Body: { "id": <uuid>, "email": <email> }
    // Success: 200 (existing) OR 201 (newly created) — both are success (idempotent).
    // Failure: any 4xx/5xx or IOException → throw UserServiceUnavailableException
}

public void deleteProfile(UUID id) {
    // DELETE /api/v1/users/internal/{id}
    // Success: 204. 404 is ALSO treated as success (already gone).
    // Failure: 5xx / IOException → log at ERROR, swallow (see §4).
}
```

Use `RestClient`'s `.retrieve().onStatus(...)` or capture `ResponseEntity` to branch on status. `MockRestServiceServer` does **not** work with `RestClient` — tests use `@RestClientTest` (see §6).

### 2.6 `UserServiceUnavailableException`

A dedicated unchecked exception thrown by `ensureProfile` on transport or 5xx failure. The global exception handler should map it to **HTTP 502** with a generic error body.

### 2.7 Startup validation

Extend the existing Auth Service startup validator to assert `INTERNAL_API_KEY` is non-blank and ≥ 32 characters. Match the `user-service/StartupValidator` behavior and error message style.

---

## 3. Call-Site Changes (exact locations)

> File names below are the ones PLAN.md §6.4 was written against after a review of the Auth Service source. If method names have drifted since, stick to the **semantics** described here, not the literal line numbers.

### 3.1 `AuthService.register()` — LOCAL registration

**Change 1 — make the method `@Transactional`.** It currently is not, which means `userRepository.save(...)` auto-commits and creates the zombie-user risk (auth row without profile). Add `@Transactional` so the whole flow is one unit of work.

**Change 2 — call `ensureProfile` after the save, before the method returns:**

```java
@Transactional
public UserResponse register(RegisterRequest req) {
    // existing: validate, hash password, save user row
    User user = userRepository.save(newUser);

    // NEW: call User Service inside the transaction.
    // Any failure (5xx, timeout, network) throws → Spring rolls back the auth row.
    userServiceClient.ensureProfile(user.getId(), user.getEmail());

    // existing: issue tokens, set cookies, build response
    return ...;
}
```

**Change 3 — move verification-email sending to after commit.** Currently (per the working assumption) `emailService.sendVerificationEmail(...)` runs inline. If the transaction rolls back for any reason (including a User Service outage), we must not send a verification email for an account that no longer exists. Use one of:

```java
TransactionSynchronizationManager.registerSynchronization(
    new TransactionSynchronization() {
        @Override public void afterCommit() {
            emailService.sendVerificationEmail(user, token);
        }
    });
```

Or — cleaner — publish an `ApplicationEvent` and listen with `@TransactionalEventListener(phase = AFTER_COMMIT)`.

Audit `emailVerificationService.generateToken()` too: if it writes to the DB, keep it inside the transaction; if it sends mail, defer it.

### 3.2 `GoogleAuthService.findOrCreateUser()` — GOOGLE OAUTH

The method shape is:

```java
return userService.findUserByEmail(email)
        .map(existing -> existing)
        .orElseGet(() -> userService.createUser(email, "google"));
```

**Only the new-user branch should call `ensureProfile`.** Putting the call after `processOAuthCallback()` returns would fire on every repeat Google login — wasteful and contractually wrong ("create profile on first OAuth login"). The correct placement:

```java
return userService.findUserByEmail(email)
        .map(existing -> existing)
        .orElseGet(() -> {
            User created = userService.createUser(email, "google");
            userServiceClient.ensureProfile(created.getId(), created.getEmail());
            return created;
        });
```

**Make this method (or the new-user lambda's surrounding call) `@Transactional`** so a User Service failure rolls back the freshly-created auth row — same invariant as `register()`.

### 3.3 `AuthController.deleteAccount()` — ACCOUNT DELETION

Current flow:
```
AuthController.deleteAccount()
  └── userService.deleteById(principal.id())   // @Transactional in UserService
```

**Call `userServiceClient.deleteProfile(principal.id())` in the controller, AFTER `userService.deleteById(...)` returns** (i.e. after the inner transaction has committed):

```java
@DeleteMapping("/delete")
public ResponseEntity<Void> deleteAccount(@CurrentUser AuthenticatedUser principal) {
    userService.deleteById(principal.id());               // commits the auth delete
    try {
        userServiceClient.deleteProfile(principal.id()); // fire-and-forget semantics
    } catch (Exception e) {
        log.error("User Service deleteProfile failed for id={}; profile may be orphaned until reconciliation",
                  principal.id(), e);
    }
    // existing: clear cookies, respond 200
    return ResponseEntity.ok().build();
}
```

**Do NOT** move this call inside `UserService.deleteById(...)`: that method is `@Transactional`, so a User Service 5xx would throw, roll back the local delete, and leave the auth row intact — the exact opposite of "log and continue."

`AuthService.deleteAccount()` does not exist; the delete lives in the controller. Verify this before refactoring.

---

## 4. Failure Semantics (explicit)

| Scenario | Response | Why |
|----------|----------|-----|
| Registration / first-time Google login — User Service unreachable or 5xx | **502** to the client; auth row **rolled back** | Call sites are `@Transactional`. Simpler invariants: no zombie auth rows. Registrations are low-frequency; brief outages are acceptable. |
| Account delete — User Service unreachable or 5xx | **200** to the client; ERROR log; auth row already deleted | User asked to be deleted; auth row is gone. Periodic reconciliation sweeps orphan profiles. |
| `ensureProfile` on an id that already has a profile (retry, double-submit) | **200 OK** from User Service; Auth Service treats as success | User Service `POST /internal/create` is race-safe idempotent. See §5.3 of the User Service PLAN. |
| `deleteProfile` on an id that has no profile | **404** from User Service; Auth Service treats as success | Already gone = the effect the caller wanted. |

The asymmetry (register is transactional, delete is fire-and-forget) is intentional. Document it in the Auth Service README.

---

## 5. Response-Status Handling in `UserServiceClient`

| Endpoint | Statuses to treat as success | Translate to |
|----------|-----------------------------|--------------|
| `POST /api/v1/users/internal/create` | `200`, `201` | return normally |
| `POST /api/v1/users/internal/create` | `401` | **non-retryable**: throw a config-error exception. Indicates `INTERNAL_API_KEY` mismatch — fail loud, alert on it. |
| `POST /api/v1/users/internal/create` | `400` | throw (malformed request — a bug, not a transient error) |
| `POST /api/v1/users/internal/create` | `5xx` / IOException / timeout | throw `UserServiceUnavailableException` → caller rolls back |
| `DELETE /api/v1/users/internal/{id}` | `204`, `404` | return normally |
| `DELETE /api/v1/users/internal/{id}` | `5xx` / IOException / timeout | throw; controller catches and logs (§3.3) |

No retries inside `UserServiceClient`. The transactional call-sites get a single attempt; the user can retry the whole registration. Adding client-side retry multiplies load during a User Service outage without improving correctness.

---

## 6. Tests (Auth Service side)

### 6.1 `UserServiceClient` — `@RestClientTest`

Use `@RestClientTest(UserServiceClient.class)`. This is the Spring Boot 3.2+ analogue of `@RestTemplateTest`; `MockRestServiceServer` **does not work with `RestClient` directly**, but `@RestClientTest` auto-wires a mock binding against the `RestClient.Builder` autoconfig.

Cover at least:
- `ensureProfile` — 200 response → returns normally
- `ensureProfile` — 201 response → returns normally
- `ensureProfile` — 401 → non-retryable exception
- `ensureProfile` — 5xx → `UserServiceUnavailableException`
- `ensureProfile` — connection refused → `UserServiceUnavailableException`
- `deleteProfile` — 204 → returns normally
- `deleteProfile` — 404 → returns normally (treated as success)
- `deleteProfile` — 5xx → throws
- Every request carries the `X-Internal-Api-Key` header with the configured value.
- Every request's `baseUrl` resolves to `user-service.base-url`.

### 6.2 Service-level tests

Use `@MockBean UserServiceClient` on the Spring test context (simpler than a real HTTP mock).

- `AuthService.register()` — happy path calls `ensureProfile` exactly once with the saved id/email.
- `AuthService.register()` — when `ensureProfile` throws, the auth `users` row is **not** present in the DB afterward (rollback assertion — use `@DataJpaTest` or full `@SpringBootTest` with Testcontainers).
- `AuthService.register()` — when `ensureProfile` throws, the verification email is **not** sent (verify the email mock / assert zero invocations on the mail sender).
- `GoogleAuthService.findOrCreateUser()` — first login calls `ensureProfile` once; second login with the same email calls it zero times.
- `GoogleAuthService.findOrCreateUser()` — new-user branch failure rolls back the auth row.
- `AuthController.deleteAccount()` — happy path deletes the auth row AND calls `deleteProfile`.
- `AuthController.deleteAccount()` — when `deleteProfile` throws, controller still returns 200 and the auth row is still gone.

---

## 7. Observability

- Add structured logs at INFO for `ensureProfile` and `deleteProfile` start/success, ERROR for failures, with `id` and `email` (never the `X-Internal-Api-Key` value).
- Expose Micrometer counters for both calls (success / failure) so the SRE team can alert on User Service unreachability without grepping logs.
- Alert on non-zero rate of `INTERNAL_API_KEY` 401s (§5) — that is always a config bug, never a transient.

---

## 8. Feature Flag (recommended)

Wrap the `ensureProfile` call (but **not** the `@Transactional` annotation, and **not** the email-deferral change) in a boolean flag, e.g. `auth.user-service.enabled`, defaulted to `true` in production and `true` in tests. The flag lets you disable the cross-service call without rolling back the deployment if the User Service misbehaves. The transactional boundary is always safe to have on — it only adds a unit of work — so that change can ship unflagged.

Delete the flag once the integration has been stable for a few weeks.

---

## 9. Checklist for the Auth Service PR

- [ ] `INTERNAL_API_KEY` and `USER_SERVICE_BASE_URL` wired in all envs, secret rotated.
- [ ] Startup fails fast when `INTERNAL_API_KEY` is blank or < 32 chars.
- [ ] `JWT_SECRET` is **byte-identical** to the User Service's value (test: a token issued here validates there using a shared fixture test).
- [ ] `AuthService.register()` is `@Transactional` and calls `ensureProfile` inside the transaction.
- [ ] Verification email moved to `AFTER_COMMIT`.
- [ ] `GoogleAuthService.findOrCreateUser()` calls `ensureProfile` **only** inside the `orElseGet` new-user branch, and the branch is transactional.
- [ ] `AuthController.deleteAccount()` calls `deleteProfile` **after** `userService.deleteById(...)` commits; exceptions are logged and swallowed.
- [ ] `@RestClientTest(UserServiceClient.class)` covers 200/201/204/404/401/5xx + header + baseUrl.
- [ ] Rollback test: `ensureProfile` throwing on register leaves zero auth rows in the DB and zero verification emails sent.
- [ ] Delete-resilience test: `deleteProfile` throwing still returns 200 to the client and still deletes the auth row.
- [ ] `httpclient5` is **not** on the classpath; `JdkClientHttpRequestFactory` is used.
- [ ] Dashboards: Micrometer counters for both call-sites; alert on 401s from User Service.
- [ ] README updated to document `INTERNAL_API_KEY`, `USER_SERVICE_BASE_URL`, failure semantics, and the register-transactional / delete-fire-and-forget asymmetry.
