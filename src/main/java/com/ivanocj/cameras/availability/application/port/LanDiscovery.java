package com.ivanocj.cameras.availability.application.port;

import com.ivanocj.cameras.availability.application.model.LanDiscoveryResult;
import java.io.IOException;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Discovers GWell LAN metadata without credentials, remote rendezvous, or a P2P session. */
@FunctionalInterface
public interface LanDiscovery {
    LanDiscoveryResult scan(Duration timeout, BooleanSupplier cancelled, Consumer<String> progress) throws IOException;
}
