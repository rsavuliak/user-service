# Auth Service API

**Base URL:** `https://auth.savuliak.com/api/v1/auth`  
**Local dev:** `http://localhost:8080/api/v1/auth`

## Overview

All authentication state is managed via HTTP-only cookies — the frontend never handles tokens directly. Requests that modify state (login, refresh, logout) set or clear cookies in the response. For cross-origin requests, include credentials:

```js
fetch(url, { credentials: "include" })
```

CORS is configured to allow credentials from `CORS_ALLOWED_ORIGINS` (default: `http://localhost:5173`).

---

## Cookies

| Cookie | Contents | HttpOnly | Secure | SameSite | Expiry |
|--------|----------|----------|--------|----------|--------|
| `token` | JWT access token | yes | yes | None | 15 min |
| `refreshToken` | Opaque refresh token | yes | yes | None | 30 days |

Both cookies use `Path=/` and the domain configured via `COOKIE_DOMAIN`.

### JWT Claims (access token)

The `token` cookie contains a signed JWT. The frontend can decode (but not verify) it to read:

```json
{
  "sub": "user-uuid",
  "email": "user@example.com",
  "provider": "local | google",
  "emailVerified": true,
  "iat": 1234567890,
  "exp": 1234568790
}
```

Use `emailVerified` to show/hide the email verification banner or restrict UI. **Important:** treat `/me` as the authoritative source — re-fetch on window-focus because the JWT claim can be up to 15 minutes stale (access token lifetime).

---

## Endpoints

### POST `/register`

Create a new account. Issues auth cookies immediately — the user is logged in. A verification email is sent in the background; `emailVerified` starts as `false` and limits certain actions until the email is confirmed.

**Request**
```json
{
  "email": "user@example.com",
  "password": "secret"
}
```

**Responses**

| Status | Body | Meaning |
|--------|------|---------|
| 201 | `UserResponse` (see below) | Account created, logged in, verification email sent |
| 400 | `{ "errors": ["email: must be a well-formed email address"] }` | Validation failed |
| 409 | `{ "error": "..." }` | Email already registered |

**201 Body**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "user@example.com",
  "provider": "local",
  "emailVerified": false
}
```

Cookies set: `token` (15 min) + `refreshToken` (30 days).

---

### GET `/verify-email?token=<token>`

Validates the token from the verification email link. On success, marks the account verified, issues auth cookies, and redirects to the frontend.

**Query params:** `token` (string, required)

**Responses**

| Status | Behavior |
|--------|----------|
| 302 | Redirect to `FRONTEND_URL`; sets `token` + `refreshToken` cookies |
| 401 | Token invalid or expired (tokens expire after 60 minutes) |

---

### POST `/resend-verification`

Re-sends the verification email. Silently succeeds for unknown or already-verified emails (prevents email enumeration).

**Request**
```json
{
  "email": "user@example.com"
}
```

**Responses**

| Status | Body | Meaning |
|--------|------|---------|
| 200 | _(empty)_ | Email sent (or silently ignored) |
| 400 | `{ "errors": [...] }` | Validation failed |
| 429 | `{ "error": "..." }` | Cooldown not met (default: 5 min between sends) |

---

### POST `/login`

Authenticate with email and password. Issues auth cookies.

**Request**
```json
{
  "email": "user@example.com",
  "password": "secret"
}
```

**Responses**

| Status | Body | Meaning |
|--------|------|---------|
| 200 | _(empty)_ | Authenticated; sets `token` + `refreshToken` cookies |
| 401 | `"Invalid credentials"` | Wrong email or password (deliberately generic) |

> Login succeeds regardless of `emailVerified` status. Check the JWT claim to decide whether to restrict the UI.

---

### POST `/refresh`

Exchange a valid `refreshToken` cookie for a new token pair. Old refresh token is invalidated.

**Request:** No body. Reads `refreshToken` cookie automatically.

**Responses**

| Status | Body | Meaning |
|--------|------|---------|
| 200 | _(empty)_ | New `token` + `refreshToken` cookies set |
| 401 | `{ "error": "..." }` | Missing, invalid, or expired refresh token |

---

### GET `/oauth/google?code=<code>`

Complete Google OAuth login. Exchange the authorization code (from Google's consent screen) for auth cookies.

**Query params:** `code` (string, required — Google OAuth2 authorization code)

**Responses**

| Status | Behavior |
|--------|----------|
| 302 | Redirect to `https://savuliak.com/`; sets `token` + `refreshToken` cookies |
| 409 | Email already registered with a different provider (e.g. local) |
| 502 | Google API call failed |

---

### GET `/me` _(protected)_

Return the current user's profile. Requires a valid `token` cookie.

**Responses**

| Status | Body | Meaning |
|--------|------|---------|
| 200 | See below | Authenticated |
| 401 | _(empty)_ | Missing or invalid token |
| 404 | `{ "error": "..." }` | User deleted since token was issued |

**200 Body**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "user@example.com",
  "provider": "local",
  "emailVerified": true
}
```

---

### POST `/logout` _(protected)_

Invalidate the refresh token and clear auth cookies.

**Request:** No body. Reads `refreshToken` cookie automatically.

**Responses**

| Status | Body | Meaning |
|--------|------|---------|
| 200 | `{ "success": true, "message": "Logged out successfully" }` | Logged out; cookies cleared |

> Returns 200 even if no refresh token cookie was present.

---

### DELETE `/delete` _(protected)_

Permanently delete the current user's account. Requires a valid `token` cookie.

**Responses**

| Status | Body | Meaning |
|--------|------|---------|
| 200 | _(empty)_ | Account deleted; cookies cleared |
| 401 | _(empty)_ | Missing or invalid token |
| 404 | `{ "error": "..." }` | User not found |

---

## Error Response Format

**Validation errors (400)**
```json
{ "errors": ["fieldName: message", "..."] }
```

**All other errors (401, 404, 409, 429, 502)**
```json
{ "error": "Human-readable message" }
```

**401 from bad credentials specifically**
```
"Invalid credentials"
```
(plain string, not JSON object)

**500**
```json
{ "error": "Internal server error" }
```

---

## Email Verification Flow

```
1. POST /register          → 202, verification email sent
2. User clicks email link  → GET /verify-email?token=...
3.                         → 302 redirect to frontend + auth cookies set
```

If the link expires (60 min) or is lost:
```
POST /resend-verification  → new email sent (5 min cooldown enforced)
```

---

## Key Behaviors

- **Email + provider uniqueness:** the same email address can exist for both `local` and `google` providers. Attempting to use Google OAuth with an email already registered as `local` returns 409.
- **Token rotation:** every call to `/refresh` invalidates the old refresh token and issues a new one.
- **No user enumeration:** `/login` returns the same 401 for a non-existent user and a wrong password. `/resend-verification` always returns 200.
- **Google users are auto-verified:** `emailVerified` is `true` immediately on Google sign-in.
- **Async email sending:** SMTP failures are logged server-side but do not surface as API errors.
- **Profile photo (Google users):** This service does not store or return profile photos. If you need the user's Google profile picture, retrieve it on the frontend during the OAuth flow (it is included in the Google userinfo response) or handle it in a dedicated profile service.
- **Cross-device email verification:** If a user registers on device A and clicks the verification link on device B, device B gets new cookies with `emailVerified: true`. Device A's refresh token is rotated and becomes invalid — it will be silently logged out after the current access token (15 min) expires. Re-fetch `/me` on window-focus to detect the updated `emailVerified` state.
- **Unverified account lifecycle:** Unverified accounts persist indefinitely — there is no expiry or purge policy at this time.
