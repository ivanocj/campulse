package com.ivanocj.cameras.availability.application.port;

import java.util.function.LongSupplier;

@FunctionalInterface
public interface SessionProbeFactory {
    SessionProbe create(DatagramTransport transport, LongSupplier nanoTime);
}
