package com.ivanocj.cameras.availability.domain.model;

import java.util.Objects;

/** Opaque, case-sensitive SDK device key; no unverified wire conversion or GWell numeric aliasing. */
public record IotVideoDeviceId(String value) implements DeviceIdentifier {
    public IotVideoDeviceId {
        value = Objects.requireNonNull(value).strip();
        if (value.isEmpty() || value.length() > 256
                || value.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new IllegalArgumentException("Enter a nonblank IoTVideo device ID without whitespace (up to 256 characters).");
        }
    }
}
