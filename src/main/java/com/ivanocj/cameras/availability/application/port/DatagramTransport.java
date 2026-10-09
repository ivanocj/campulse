package com.ivanocj.cameras.availability.application.port;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;

public interface DatagramTransport extends AutoCloseable {
    void send(byte[] bytes, InetSocketAddress destination) throws IOException;

    Optional<Received> receive(Duration wait) throws IOException;

    InetSocketAddress localAddress();

    @Override
    void close();

    record Received(byte[] bytes, InetSocketAddress source) {
    }
}
