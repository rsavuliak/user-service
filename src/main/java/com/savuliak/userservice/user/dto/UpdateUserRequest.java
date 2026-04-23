package com.savuliak.userservice.user.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import org.openapitools.jackson.nullable.JsonNullable;

import java.util.Map;

/**
 * PATCH semantics:
 *  - {@code JsonNullable<String>} fields: absent → don't touch; explicit
 *    null → clear; value → set. All size/format checks happen in
 *    {@code UserService} because Bean Validation does not traverse
 *    {@code JsonNullable} — any @Size/@URL on these fields would be a silent
 *    no-op.
 *  - {@code settings}: plain Map. Absent → don't touch. Present and non-null →
 *    shallow-merge into existing settings. Explicit null → 400. The column
 *    is NOT NULL, so there is no "clear to null" semantic.
 */
public class UpdateUserRequest {

    private JsonNullable<String> displayName = JsonNullable.undefined();
    private JsonNullable<String> avatarUrl = JsonNullable.undefined();
    private Map<String, Object> settings;
    private boolean settingsExplicitlyNull;

    public JsonNullable<String> getDisplayName() {
        return displayName;
    }

    public void setDisplayName(JsonNullable<String> displayName) {
        this.displayName = displayName;
    }

    public JsonNullable<String> getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(JsonNullable<String> avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public Map<String, Object> getSettings() {
        return settings;
    }

    public boolean isSettingsExplicitlyNull() {
        return settingsExplicitlyNull;
    }

    /**
     * Captures the case where the client sent {@code "settings": null} so the
     * service can reject it as 400. Without {@code Nulls.SET}, Jackson would
     * skip the setter on null and we could not distinguish absent from null.
     */
    @JsonSetter(value = "settings", nulls = Nulls.SET)
    public void setSettings(Map<String, Object> value) {
        this.settings = value;
        this.settingsExplicitlyNull = (value == null);
    }
}
