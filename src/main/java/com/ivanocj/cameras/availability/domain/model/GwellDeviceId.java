package com.ivanocj.cameras.availability.domain.model;

import java.util.Objects;

/** Numeric GWell identity. GwMsgKits sets bit 31 when the user-visible ID starts with zero. */
public record GwellDeviceId(String value) implements DeviceIdentifier {
    public GwellDeviceId {
        value = Objects.requireNonNull(value).strip();
        if (!value.matches("[0-9]{1,11}")) {
            throw new IllegalArgumentException("Enter a numeric GWell ID (preserve its leading zero).");
        }
        long serial = Long.parseLong(value);
        if (serial < 1 || serial > 0x7fff_ffffL) {
            throw new IllegalArgumentException("GWell serial must be between 1 and 2147483647 before the namespace bit.");
        }
    }

    public int wireValue() {
        int serial = (int) Long.parseLong(value);
        return value.startsWith("0") ? serial | 0x8000_0000 : serial;
    }

    public static GwellDeviceId fromWire(int identity) {
        return new GwellDeviceId((identity < 0 ? "0" : "") + (identity & 0x7fff_ffff));
    }
}
