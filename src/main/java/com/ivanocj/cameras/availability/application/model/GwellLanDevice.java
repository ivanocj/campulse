package com.ivanocj.cameras.availability.application.model;

import com.ivanocj.cameras.availability.domain.model.GwellDeviceId;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.OptionalInt;

/** Unauthenticated LAN discovery metadata, not a P2P session or an alive-confirmed media endpoint. */
public record GwellLanDevice(GwellDeviceId identifier, InetSocketAddress endpoint, int deviceType, int subType,
                             int flags, Optional<String> macAddress, OptionalInt model) {
}
