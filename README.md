# user-service

Spring Boot 3.x / Java 21 microservice that owns user profile data (display name, avatar URL, JSONB settings, balance). Companion to the Auth Service — trusts JWTs it issues, does not handle authentication itself.

See `PLAN.md` for the full design spec and `auth-service-api/API.md` for the Auth Service's public contract.

## Prerequisites

- Java 21 (verified with OpenJDK 21.0.1).
- Docker (for local Postgres and for Testcontainers in tests).
- Maven wrapper is bundled — no system Maven required.

## Environment variables

Copy `.env.example` to `.env` and fill in:

- `JWT_SECRET` — **Base64-encoded** HMAC key. Must be the exact same string the Auth Service uses for its own `JWT_SECRET`. Both services decode via `Base64.getDecoder().decode(...)` before `Keys.hmacShaKeyFor(...)`, so the byte arrays — and therefore the signing key — are identical. Must decode to >= 32 bytes (HS256 minimum).
- `INTERNAL_API_KEY` — shared secret for the Auth Service's server-to-server calls. Min 32 characters.
- `CORS_ALLOWED_ORIGINS` — comma-separated.
- `DB_USERNAME` / `DB_PASSWORD` — default `postgres` / `postgres`.
- `SPRING_DATASOURCE_URL` — optional. Defaults to `jdbc:postgresql://localhost:5433/user_service` for local dev. Set in production.
- `SERVER_PORT` — optional. Defaults to `8081`.

Startup fails fast (with an actionable message) if any required value is missing or malformed.

## Health checks

Actuator probes are exposed for orchestrators:

- `GET /actuator/health/liveness` — process is alive.
- `GET /actuator/health/readiness` — alive **and** the DB is reachable (readiness group includes `db`).
- `GET /actuator/health` — aggregate. `show-details: never`, so no internals leak.

These paths are outside `/api/v1/users/**`, so neither the JWT interceptor nor the internal-API-key filter runs on them.

## Docker

```bash
docker build -t user-service:local .
docker run --rm -p 8081:8081 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5433/user_service \
  -e JWT_SECRET=... -e INTERNAL_API_KEY=... -e CORS_ALLOWED_ORIGINS=https://savuliak.com \
  user-service:local
```

The image is a multi-stage build (JDK 21 builder → JRE 21 runtime) using Spring Boot's layered-jar format for better layer caching. It runs as the non-root `app` user.

## Run locally

```bash
docker compose up -d postgres
JWT_SECRET=$(openssl rand -base64 48) \
INTERNAL_API_KEY=$(openssl rand -base64 48) \
./mvnw spring-boot:run
```

The service listens on `http://localhost:8081`.

> `docker compose up -d postgres` still starts **only** Postgres (service name is explicit). Plain `docker compose up` now also builds and starts the app container — use the explicit form while developing against `./mvnw spring-boot:run`.
>
> Local dev picks up `docker-compose.override.yml`, which adds host port `5433:5432` to Postgres so you can connect from the host (`psql`, IDE, `./mvnw spring-boot:run`). The deploy workflow excludes that file from the rsync so production does **not** expose Postgres on the host — the app container reaches it over the Docker network.

## Test

```bash
./mvnw verify
```

Unit tests run via Surefire; integration tests (`*IT.java`) run via Failsafe against a real Postgres 16 Testcontainer.

## Endpoints

### Public (require the `token` cookie)

- `GET /api/v1/users/me` — full profile. `emailVerified` is read from the JWT, not the DB.
- `PATCH /api/v1/users/me` — partial update. See PATCH semantics below.
- `GET /api/v1/users/{id}/public` — `id`, `displayName`, `avatarUrl` only. No auth required.

### Internal (require `X-Internal-Api-Key`)

- `POST /api/v1/users/internal/create` — idempotent upsert. Returns 201 for new, 200 for existing.
- `DELETE /api/v1/users/internal/{id}` — 204 on success, 404 if not found.

## PATCH semantics

`PATCH /me` distinguishes three states per field:

| Field on request | `displayName` / `avatarUrl` | `settings` |
|---|---|---|
| absent from JSON | don't touch | don't touch |
| `null` | clear the field | 400 (column is NOT NULL) |
| value | set (with validation) | shallow-merge into existing settings |

- `displayName` and `avatarUrl` use `JsonNullable<String>` so the three-way distinction works.
- `settings` is a plain `Map<String, Object>` — it has no "clear to null" semantic because the column is `NOT NULL` at the schema level.
- No Bean Validation annotations on `JsonNullable` fields. Hibernate Validator does not traverse the `JsonNullable` wrapper, so `@Size`/`@URL` there would be silent no-ops. Size and URL checks for these fields run in `UserService` after unwrapping.

## Project layout

See `PLAN.md` §9 for the full tree.

Key packages:

- `com.savuliak.userservice.config` — property records, startup validator, Web/CORS config.
- `com.savuliak.userservice.security` — JWT parser, interceptor, `@CurrentUser` resolver. No Spring Security.
- `com.savuliak.userservice.user` — entity, repository, services, controllers, DTOs.
- `com.savuliak.userservice.common` — internal-API-key filter, global exception handler.

## Deployment

CI/CD: `.github/workflows/deploy.yml` — push to `main` runs `./mvnw verify`, then SSHes to the DigitalOcean droplet, rsyncs the repo to `/home/deploy/user-service`, and runs `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build`. A post-deploy readiness probe waits for the container healthcheck to flip to `healthy` before marking the job green.

The production overlay (`docker-compose.prod.yml`) joins the app container to the shared `gateway-network` so `nginx-proxy` + `acme-companion` can terminate TLS at `https://users.savuliak.com`. No host ports are published in production — the frontend hits the service only via nginx-proxy.

**Required GitHub repo secrets** (Settings → Secrets and variables → Actions):

- `DO_HOST` — droplet IP or DNS
- `DO_USER` — SSH user (e.g. `deploy`)
- `DO_KEY_CI` — SSH private key; the public key must be in `~/.ssh/authorized_keys` on the droplet

**Droplet-local runtime secrets** live in `/home/deploy/user-service/.env` (chmod 600, never in git):

```env
JWT_SECRET=...                      # Base64, >=32 decoded bytes, IDENTICAL to auth-service
INTERNAL_API_KEY=...                # >=32 chars, IDENTICAL to auth-service
CORS_ALLOWED_ORIGINS=https://savuliak.com
DB_USERNAME=postgres
DB_PASSWORD=...                     # rotate from default
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/user_service
```

The deploy step fails fast if `.env` is missing. Generate shared secrets once with `openssl rand -base64 48` and copy the identical values to the auth-service environment.

## What this service does NOT do

- Authentication / login / registration (Auth Service handles that).
- File upload (avatar is a URL).
- Rate limiting, caching, email sending.
- Spring Security — JWT validation is a plain `HandlerInterceptor` + a servlet filter for the internal API key.
