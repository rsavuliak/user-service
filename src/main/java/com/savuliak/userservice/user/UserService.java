package com.savuliak.userservice.user;

import com.savuliak.userservice.security.AuthenticatedUser;
import com.savuliak.userservice.user.dto.CreateUserRequest;
import com.savuliak.userservice.user.dto.PublicUserResponse;
import com.savuliak.userservice.user.dto.UpdateUserRequest;
import com.savuliak.userservice.user.dto.UserResponse;
import com.savuliak.userservice.user.exception.InvalidSettingsException;
import com.savuliak.userservice.user.exception.UserNotFoundException;
import com.savuliak.userservice.user.exception.ValidationException;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // No @Transactional here: each repo call runs in its own transaction. A
    // DataIntegrityViolationException from the INSERT must not mark an
    // enclosing transaction as rollback-only, or the subsequent re-fetch
    // and method return would trigger UnexpectedRollbackException.
    public EnsureProfileResult ensureProfile(CreateUserRequest req) {
        return userRepository.findById(req.id())
                .map(existing -> new EnsureProfileResult(existing, false))
                .orElseGet(() -> insertOrLoseRace(req.id()));
    }

    private EnsureProfileResult insertOrLoseRace(UUID id) {
        try {
            User saved = userRepository.save(User.builder().id(id).build());
            return new EnsureProfileResult(saved, true);
        } catch (DataIntegrityViolationException e) {
            // Lost the race with a concurrent create for the same id — the other writer's
            // row is authoritative. Re-read and return as "already existed".
            User existing = userRepository.findById(id).orElseThrow(() -> e);
            return new EnsureProfileResult(existing, false);
        }
    }

    public void deleteById(UUID id) {
        if (!userRepository.existsById(id)) {
            throw new UserNotFoundException("User not found");
        }
        userRepository.deleteById(id);
    }

    public UserResponse getMe(AuthenticatedUser principal) {
        User user = userRepository.findById(principal.id())
                .orElseThrow(() -> new UserNotFoundException("User not found"));
        return toUserResponse(user, principal.emailVerified());
    }

    public UserResponse updateMe(AuthenticatedUser principal, UpdateUserRequest req) {
        User user = userRepository.findById(principal.id())
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        applyDisplayName(user, req.getDisplayName());
        applyAvatarUrl(user, req.getAvatarUrl());
        applySettings(user, req);

        User saved = userRepository.save(user);
        return toUserResponse(saved, principal.emailVerified());
    }

    public PublicUserResponse getPublic(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found"));
        return new PublicUserResponse(user.getId(), user.getDisplayName(), user.getAvatarUrl());
    }

    private void applyDisplayName(User user, JsonNullable<String> field) {
        if (!field.isPresent()) {
            return;
        }
        String value = field.get();
        if (value == null) {
            user.setDisplayName(null);
            return;
        }
        String trimmed = value.trim();
        if (trimmed.length() > 100) {
            throw new ValidationException("displayName: must not exceed 100 characters");
        }
        user.setDisplayName(trimmed);
    }

    private void applyAvatarUrl(User user, JsonNullable<String> field) {
        if (!field.isPresent()) {
            return;
        }
        String value = field.get();
        if (value == null) {
            user.setAvatarUrl(null);
            return;
        }
        if (value.length() > 500) {
            throw new ValidationException("avatarUrl: must not exceed 500 characters");
        }
        try {
            new URI(value).toURL();
        } catch (URISyntaxException | MalformedURLException | IllegalArgumentException e) {
            throw new ValidationException("avatarUrl: must be a valid URL");
        }
        user.setAvatarUrl(value);
    }

    private void applySettings(User user, UpdateUserRequest req) {
        if (req.isSettingsExplicitlyNull()) {
            throw new InvalidSettingsException("settings: must not be null");
        }
        Map<String, Object> incoming = req.getSettings();
        if (incoming == null) {
            return;
        }
        Map<String, Object> merged = new HashMap<>(user.getSettings());
        merged.putAll(incoming);
        user.setSettings(merged);
    }

    private UserResponse toUserResponse(User user, boolean emailVerified) {
        return new UserResponse(
                user.getId(),
                user.getDisplayName(),
                user.getAvatarUrl(),
                user.getSettings(),
                user.getBalance(),
                emailVerified,
                user.getCreatedAt());
    }
}
