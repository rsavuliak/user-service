package com.savuliak.userservice.security;

import java.util.UUID;

public record AuthenticatedUser(UUID id, String email, boolean emailVerified, String provider) {
}
