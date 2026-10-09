package com.ivanocj.cameras.availability.infrastructure.transport;

import com.ivanocj.cameras.availability.application.port.DatagramTransport;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

public final class UdpTransport implements DatagramTransport {
    private final DatagramSocket socket;
    private final InetSocketAddress local;
    private final byte[] buffer = new byte[2048];

    public UdpTransport(InetSocketAddress routeTo) throws IOException {
        this(routeTo, 0);
    }

    /** GWell discovery receives on its fixed port; PPCS/XQ keep their original ephemeral socket. */
    public UdpTransport(InetSocketAddress routeTo, int listenPort) throws IOException {
        socket = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), listenPort));
        try (var route = new DatagramSocket()) {
            socket.setBroadcast(true);
            route.connect(routeTo);
            local = new InetSocketAddress(route.getLocalAddress(), socket.getLocalPort());
        } catch (IOException | RuntimeException failed) {
            socket.close();
            throw failed;
        }
    }

    @Override
    public void send(byte[] bytes, InetSocketAddress destination) throws IOException {
        socket.send(new DatagramPacket(bytes, bytes.length, destination));
    }

    @Override
    public Optional<Received> receive(Duration wait) throws IOException {
        socket.setSoTimeout((int) Math.max(1, wait.toMillis()));
        var packet = new DatagramPacket(buffer, buffer.length);
        try {
            socket.receive(packet);
        } catch (SocketTimeoutException timeout) {
            return Optional.empty();
        }
        return Optional.of(new Received(Arrays.copyOf(packet.getData(), packet.getLength()),
                (InetSocketAddress) packet.getSocketAddress()));
    }

    @Override
    public InetSocketAddress localAddress() {
        return local;
    }

    @Override
    public void close() {
        socket.close();
    }
}
