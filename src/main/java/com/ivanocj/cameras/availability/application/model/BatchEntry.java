package com.ivanocj.cameras.availability.application.model;

import java.util.Optional;

/** A source record, including its description, detected deployment, or a validation explanation. */
public record BatchEntry(int lineNumber, String code, String description, Optional<ServerProfile> profile, String error) {
    public BatchEntry(int lineNumber, String code, Optional<ServerProfile> profile, String error) {
        this(lineNumber, code, "", profile, error);
    }

    public boolean canCheck() {
        return profile.isPresent() && error.isEmpty();
    }
}
