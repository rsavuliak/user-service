package com.savuliak.userservice.user.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * {@code emailVerified} is sourced from the JWT claim on each request, not
 * persisted in this service. {@code balance} has no write path in the
 * current API — it is a placeholder for a future billing flow.
 */
public record UserResponse(
        UUID id,
        String displayName,
        String avatarUrl,
        Map<String, Object> settings,
        BigDecimal balance,
        boolean emailVerified,
        OffsetDateTime createdAt
) {
}
