package com.ivanocj.cameras.availability.domain.model;

import java.util.Locale;

public record DeviceId(String value) implements DeviceIdentifier {
    public DeviceId {
        value = value.strip().toUpperCase(Locale.ROOT);
        if (value.length() > 23 || !value.matches("[A-Z][A-Z0-9]{0,6}-[0-9]{1,10}-[A-Z0-9]{1,7}")) {
            throw new IllegalArgumentException("Enter a DID in PREFIX-SERIAL-CODE format.");
        }
        if (Long.parseLong(value.split("-")[1]) > 0xffff_ffffL) {
            throw new IllegalArgumentException("DID serial must fit an unsigned 32-bit integer.");
        }
    }

    public String prefix() {
        return value.substring(0, value.indexOf('-'));
    }
}
