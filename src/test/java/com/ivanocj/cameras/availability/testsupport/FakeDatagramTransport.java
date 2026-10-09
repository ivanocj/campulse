package com.ivanocj.cameras.availability.testsupport;

import com.ivanocj.cameras.availability.application.port.DatagramTransport;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Optional;

public class FakeDatagramTransport implements DatagramTransport {
    public final ArrayDeque<Received> incoming = new ArrayDeque<>();
    public long nanos;

    @Override
    public void send(byte[] bytes, InetSocketAddress destination) throws IOException {
    }

    @Override
    public Optional<Received> receive(Duration wait) {
        nanos += wait.toNanos();
        return Optional.ofNullable(incoming.poll());
    }

    @Override
    public InetSocketAddress localAddress() {
        return new InetSocketAddress("127.0.0.1", 40000);
    }

    @Override
    public void close() {
    }
}
