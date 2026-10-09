package com.ivanocj.cameras.availability.domain.model;

public enum TransportProtocol {
    PPCS, XQ, GWELL, IOTVIDEO;

    public boolean legacyUdp() {
        return this == PPCS || this == XQ;
    }

    public DeviceIdentifier parseIdentifier(String value) {
        return switch (this) {
            case PPCS, XQ -> new DeviceId(value);
            case GWELL -> new GwellDeviceId(value);
            case IOTVIDEO -> new IotVideoDeviceId(value);
        };
    }

    public void validate(DeviceIdentifier identifier) {
        boolean compatible = switch (this) {
            case PPCS, XQ -> identifier instanceof DeviceId;
            case GWELL -> identifier instanceof GwellDeviceId;
            case IOTVIDEO -> identifier instanceof IotVideoDeviceId;
        };
        if (!compatible) {
            throw new IllegalArgumentException(this + " needs its own protocol-specific identifier.");
        }
        if (this == XQ && identifier.value().length() > 20) {
            throw new IllegalArgumentException("XQ DIDs must fit the 20-byte ASCII identity field.");
        }
    }

    /** Serializes equivalent sessions without erasing protocol-specific identity distinctions. */
    public String sessionKey(DeviceIdentifier identifier) {
        validate(identifier);
        return name() + ":" + switch (this) {
            case PPCS -> identifier.value().replaceFirst("^([A-Z0-9]+)-0+(?=[0-9])", "$1-");
            case GWELL -> Integer.toUnsignedString(((GwellDeviceId) identifier).wireValue());
            case XQ, IOTVIDEO -> identifier.value();
        };
    }
}
