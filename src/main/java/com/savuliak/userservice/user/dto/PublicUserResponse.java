package com.savuliak.userservice.user.dto;

import java.util.UUID;

public record PublicUserResponse(UUID id, String displayName, String avatarUrl) {
}
