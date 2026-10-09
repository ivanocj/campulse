package com.ivanocj.cameras.availability.domain.model;

import java.util.Objects;

/** Protocol-specific identifiers. A Yoosee identifier must never be encoded as a composite PPCS DID. */
public sealed interface DeviceIdentifier permits DeviceId, GwellDeviceId, IotVideoDeviceId {
    String value();

    default String batchValue() {
        return switch (this) {
            case DeviceId did -> did.value();
            case GwellDeviceId id -> "GWELL:" + id.value();
            case IotVideoDeviceId id -> "IOTVIDEO:" + id.value();
        };
    }

    /** Batch files require an explicit family for non-composite identifiers; numbers alone are ambiguous. */
    static DeviceIdentifier parseBatch(String input) {
        var value = Objects.requireNonNull(input).strip();
        if (value.regionMatches(true, 0, "GWELL:", 0, 6)) {
            return new GwellDeviceId(value.substring(6));
        }
        if (value.regionMatches(true, 0, "IOTVIDEO:", 0, 9)) {
            return new IotVideoDeviceId(value.substring(9));
        }
        if (value.matches("[0-9]+")) {
            throw new IllegalArgumentException("Numeric IDs need an explicit GWELL: or IOTVIDEO: family; not tested.");
        }
        return new DeviceId(value);
    }
}
