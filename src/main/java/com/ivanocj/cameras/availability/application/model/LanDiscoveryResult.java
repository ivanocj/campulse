package com.ivanocj.cameras.availability.application.model;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

public record LanDiscoveryResult(List<GwellLanDevice> devices, boolean cancelled, Duration elapsed,
                                 Optional<InetSocketAddress> localEndpoint) {
    public LanDiscoveryResult {
        devices = List.copyOf(devices);
    }
}
