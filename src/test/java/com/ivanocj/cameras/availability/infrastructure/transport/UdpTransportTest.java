package com.ivanocj.cameras.availability.infrastructure.transport;

import com.ivanocj.cameras.availability.infrastructure.protocol.PpcsPacket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class UdpTransportTest {
    @Test
    void exchangesRealUdpDatagramsUsingOnlyJavaSockets() throws Exception {
        try (var server = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
             var client = new UdpTransport(new InetSocketAddress(server.getLocalAddress(), server.getLocalPort()))) {
            server.setSoTimeout(1000);
            byte[] hello = new PpcsPacket(0, new byte[0]).encode();
            client.send(hello, new InetSocketAddress(server.getLocalAddress(), server.getLocalPort()));
            var incoming = new DatagramPacket(new byte[100], 100);
            server.receive(incoming);
            server.send(new DatagramPacket(hello, hello.length, incoming.getSocketAddress()));
            assertArrayEquals(Arrays.copyOf(incoming.getData(), incoming.getLength()),
                    client.receive(Duration.ofSeconds(1)).orElseThrow().bytes());
        }
    }

    @Test
    void aReceiveTimeoutIsAnEmptyPollRatherThanAFailedProbe() throws Exception {
        try (var client = new UdpTransport(new InetSocketAddress("127.0.0.1", 32100))) {
            assertTrue(client.receive(Duration.ofMillis(10)).isEmpty());
        }
    }
}
