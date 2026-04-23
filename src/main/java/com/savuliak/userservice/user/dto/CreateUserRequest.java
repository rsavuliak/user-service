package com.savuliak.userservice.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateUserRequest(
        @NotNull UUID id,
        @NotBlank @Email String email
) {
}
