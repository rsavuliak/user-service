# User Service API — Frontend Integration Guide

**Base URL:** `https://users.savuliak.com/api/v1/users`
**Local dev:** `http://localhost:8081/api/v1/users`

Companion service to the Auth Service. Owns user profile data (display name, avatar URL, settings, balance). It does **not** handle login, registration, tokens, or cookies — those are the Auth Service's job (`auth-service-api/API.md`).

---

## Authentication

Same cookie as the Auth Service — no changes to how the frontend authenticates.

- Send credentials on every call: `fetch(url, { credentials: "include" })`.
- The `token` cookie (set by the Auth Service) is read server-side; the frontend never touches it.
- If the cookie is missing, expired, or invalid → `401`.

CORS is configured; the browser handles preflight automatically.

### Email verification — what this service does (and doesn't) do

- **Source of truth:** the `emailVerified` flag lives on the JWT, not in this service's DB. Every response reads it from the current `token` cookie.
- **Not enforced here:** this service accepts any valid-token request, verified or not. `GET /me` and `PATCH /me` work exactly the same for unverified users. If a feature should be gated on verification, **the frontend** is responsible for that gate — either by decoding the JWT directly or by checking `UserResponse.emailVerified` from `GET /me`.
- **Stale by up to ~15 min:** the JWT lifetime caps how quickly a freshly-verified state shows up in `emailVerified`. Three patterns for the frontend:
  1. **On window focus / route change** — re-fetch `GET /me` to pick up the latest value.
  2. **When the user just clicked "resend" or returned from a verification link** — call the auth-service's `POST /auth/refresh` **first**, then re-fetch `GET /me`. Refresh rotates the token with the current DB value, so the flag updates immediately.
  3. **Inside a gated action** — if a button requires verification, and the JWT says `false`, surface "please verify your email" without calling this service; you already know it would succeed server-side (no gate) but your UX gate is a frontend concern.
- **Do not cache `emailVerified` yourself** beyond the lifetime of the JWT — rely on the JWT's `exp` or re-fetch via `/me`.

```js
// Typical flow after a verification action elsewhere in the app:
await fetch("/api/v1/auth/refresh", { method: "POST", credentials: "include" });
const me = await fetch("/api/v1/users/me", { credentials: "include" }).then(r => r.json());
// me.emailVerified is now fresh
```

---

## Endpoints

### `GET /me` _(protected)_

Full profile of the current user.

**Responses**

| Status | Body | Meaning |
|---|---|---|
| 200 | `UserResponse` (below) | Authenticated |
| 401 | `{ "error": "Unauthorized" }` | Missing/expired/invalid `token` cookie |
| 404 | `{ "error": "User not found" }` | Profile does not exist for this user (shouldn't happen in steady state — auth-service creates it at registration) |

**`UserResponse`**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "displayName": "Jane Doe",
  "avatarUrl": "https://cdn.example.com/u/jane.jpg",
  "settings": { "theme": "dark", "locale": "en-US" },
  "balance": "0.00",
  "emailVerified": true,
  "createdAt": "2026-01-15T14:03:11.123Z"
}
```

Notes:
- `displayName` and `avatarUrl` may be `null` if the user hasn't set them.
- `settings` is an arbitrary JSON object — **namespace your keys** (e.g. `"ui.theme"`, `"notifications.email"`) to avoid collisions.
- `balance` is a string-encoded decimal (standard Jackson treatment of `BigDecimal`). Placeholder for a future billing flow — ignore for now.
- `emailVerified` is read from the JWT, not the DB. It can be up to ~15 min stale after the user verifies their email. Pattern: re-fetch `/me` on window-focus, or call `POST /auth/refresh` first if the stale flag would block a user action.
- `createdAt` is ISO-8601 UTC.

---

### `PATCH /me` _(protected)_

Partial update of the current user's profile. **Read the semantics carefully** — `null` and "absent from JSON" mean different things.

**Request shape**
```json
{
  "displayName": "new name",      // optional
  "avatarUrl": "https://...",     // optional
  "settings": { "theme": "light" } // optional, shallow-merge
}
```

**Per-field rules**

| Field | If **absent** from JSON | If `null` | If a value |
|---|---|---|---|
| `displayName` | don't touch | **clear the field** | set (max 100 chars) |
| `avatarUrl`   | don't touch | **clear the field** | set (max 500 chars, must be a valid URL) |
| `settings`    | don't touch | **400** (server rejects) | shallow-merge into existing settings |

Critical frontend-side implications:

- **To clear a name or avatar** → send `{"displayName": null}` in the body, not an empty string or omitted key.
- **To leave a field alone** → omit the key entirely. Do not send `undefined` as a value (most JSON stringifiers drop it, which is what you want, but some custom serializers coerce to `null`).
- **`settings` merges at the top level only.** Sending `{"settings": {"theme": "light"}}` preserves any other top-level keys (`locale`, `notifications`, etc.). If you want to clear a key inside settings, send it explicitly with value `null`.
- **`settings: null` is an error, not a clear.** The underlying column is NOT NULL.

**Responses**

| Status | Body | Meaning |
|---|---|---|
| 200 | `UserResponse` | Updated; same shape as `GET /me` |
| 400 | `{ "errors": ["..."] }` | Validation failed (too long / bad URL / `settings: null` / malformed body) |
| 401 | `{ "error": "Unauthorized" }` | Missing/invalid cookie |
| 404 | `{ "error": "User not found" }` | Profile missing (shouldn't happen) |

**Example — change display name, leave everything else alone**
```js
await fetch("https://api.savuliak.com/api/v1/users/me", {
  method: "PATCH",
  credentials: "include",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ displayName: "New Name" }),
});
```

**Example — add a setting without clobbering others**
```js
await fetch("/api/v1/users/me", {
  method: "PATCH",
  credentials: "include",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ settings: { theme: "light" } }),
});
// Afterwards, existing keys like `locale` are still present.
```

---

### `GET /{id}/public` _(no auth required)_

Public view of any user — for rendering authorship, profile cards, etc. without needing the viewer to be logged in as that user.

**Path parameter:** `id` — user UUID.

**Responses**

| Status | Body | Meaning |
|---|---|---|
| 200 | `PublicUserResponse` (below) | Found |
| 404 | `{ "error": "User not found" }` | Unknown id |

**`PublicUserResponse`**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "displayName": "Jane Doe",
  "avatarUrl": "https://cdn.example.com/u/jane.jpg"
}
```

Only `id`, `displayName`, and `avatarUrl` are ever returned. No email, no settings, no balance, no timestamps.

---

## Error envelope

**Validation errors (400)**
```json
{ "errors": ["displayName: must be at most 100 characters"] }
```
The `errors` field is always an array of `"<field>: <message>"` strings (or `"<category>: <message>"` for body-level problems). Render as a bulleted list next to the relevant form field.

**All other 4xx / 5xx**
```json
{ "error": "Human-readable message" }
```

Typical statuses you'll see:
- `400` — request body problems (always has `errors` array).
- `401` — missing/invalid/expired `token` cookie. Kick the user to re-login via the Auth Service `/login` or `/refresh` flow.
- `404` — user does not exist (for `/public`), or — rarely — the current user's profile row is missing.
- `500` — `{ "error": "Internal server error" }`. Retry once with backoff; if persistent, file a bug.

---

## Recommended frontend patterns

**1. Profile hydration on app load**

```js
const me = await fetch("/api/v1/users/me", { credentials: "include" })
  .then(r => r.ok ? r.json() : Promise.reject(r.status));
```

On 401, run the existing auth flow (the Auth Service's `/refresh` first; if that also fails, redirect to login).

**2. Settings as a namespaced object**

Put UI state your frontend needs to persist (theme, dismissed-banners-list, feature-flag opt-ins) into `settings` with stable keys. Prefer dotted or nested namespaces you control: `"ui.theme"`, `"notifications.email.weekly"`. Anything written here survives across devices.

Don't store anything sensitive in `settings` — it is not encrypted and is returned verbatim on every `GET /me`.

**3. Avatar re-fetch after upload**

This service does **not** accept uploads — `avatarUrl` is a URL field. If you have an upload flow (S3 / Cloudinary / etc.), upload first, then `PATCH /me` with the resulting URL.

**4. Stale `emailVerified` after the user verifies**

The `emailVerified` flag in `UserResponse` is taken from the current access-token JWT. If a user just verified their email in a different tab and your page has a stale token, the value may still say `false` for up to the remaining JWT lifetime (~15 min max). Refresh the token via the Auth Service's `POST /refresh` before re-fetching `/me` if the UI depends on this flag for gating.

**5. Optimistic updates for PATCH**

Safe to apply the new value optimistically. On a 400, roll back and show the `errors` array. On a 5xx, roll back and show a generic "try again" toast.

---

## What this service does NOT do

- Login / registration / email verification / password reset → Auth Service (`/api/v1/auth/...`).
- Listing or searching users → not exposed to the frontend.
- Avatar image upload → the frontend is responsible for hosting the image and sending a URL.
- Deleting an account → Auth Service's `DELETE /auth/delete` (which internally deletes the profile too).
- Rate limiting → not currently enforced at this layer. Don't spam.

---

## Changelog

| Date | Change |
|---|---|
| 2026-04-23 | Initial release — `GET /me`, `PATCH /me`, `GET /{id}/public`. |
