package com.savuliak.userservice.user.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Deliberately narrower than {@code UserResponse} — the Auth Service caller has
 * no JWT context, so fields like {@code emailVerified}, {@code balance}, and
 * {@code settings} would be misleading here.
 */
public record InternalCreateResponse(UUID id, OffsetDateTime createdAt) {
}
