package com.ivanocj.cameras.availability.integration.transport;

import com.ivanocj.cameras.availability.application.model.P2pInformation;
import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.service.AvailabilityCheckService;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.infrastructure.probe.UdpSessionProbe;
import com.ivanocj.cameras.availability.infrastructure.protocol.PpcsMessages;
import com.ivanocj.cameras.availability.infrastructure.protocol.PpcsPacket;
import com.ivanocj.cameras.availability.infrastructure.transport.UdpTransport;
import com.ivanocj.cameras.availability.testsupport.DeviceFixtures;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LoopbackProbeTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void theDefaultJavaRunnerCompletesARealUdpHandshakeAndClosesTheSession(boolean extendedPeerReplies) throws Exception {
        byte[] identity = HexFormat.of().parseHex("464646460000000000026ac94542444143000000");
        byte[] replyIdentity = extendedPeerReplies
                ? HexFormat.of().parseHex("464646460000000000026ac94542444143000000" + "01020300") : identity;
        try (var server = socket(); var peer = socket(); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var listening = new CountDownLatch(2);
            var serverTask = workers.submit(() -> {
                listening.countDown();
                var hello = receive(server);
                assertEquals(0x00, PpcsPacket.decode(data(hello)).type());
                send(server, hello, 1, PpcsMessages.address((InetSocketAddress) hello.getSocketAddress()));
                var connect = receive(server);
                var request = PpcsPacket.decode(data(connect));
                assertEquals(0x20, request.type());
                assertArrayEquals(identity, Arrays.copyOf(request.payload(), 20));
                send(server, connect, 0x40, PpcsMessages.address(endpoint(peer)));
                return true;
            });
            var peerTask = workers.submit(() -> {
                listening.countDown();
                var punch = receive(peer);
                assertArrayEquals(identity, PpcsPacket.decode(data(punch)).payload());
                if (extendedPeerReplies) {
                    send(peer, punch, 0x41, replyIdentity);
                    var response = PpcsPacket.decode(data(receive(peer)));
                    assertEquals(0x41, response.type());
                    assertArrayEquals(identity, response.payload());
                }
                send(peer, punch, 0x42, replyIdentity);
                var alive = receive(peer);
                assertEquals(0xe0, PpcsPacket.decode(data(alive)).type());
                send(peer, alive, 0xe1, new byte[0]);
                return PpcsPacket.decode(data(receive(peer))).type() == 0xf0;
            });
            assertTrue(listening.await(2, TimeUnit.SECONDS), "The local fixture must start before the probe deadline");
            var result = runner().check(new ProbeRequest(DeviceFixtures.DID,
                    List.of(endpoint(server)), Duration.ofSeconds(2), false), () -> false, message -> { });
            assertEquals(ProbeResult.Status.REACHABLE_P2P, result.status(),
                    () -> result.detail() + "; P2P information: " + result.p2pInformation());
            assertTrue(result.serverResponded());
            assertEquals(endpoint(peer), result.peer().orElseThrow());
            assertEquals(Optional.of(TransportProtocol.PPCS), result.p2pInformation().protocol());
            assertEquals(P2pInformation.ConnectionMode.DIRECT_P2P, result.p2pInformation().mode());
            assertEquals(List.of(endpoint(peer)), result.p2pInformation().deviceCandidates());
            assertEquals(List.of(endpoint(server)), result.p2pInformation().respondingServers());
            assertTrue(result.p2pInformation().localEndpoint().isPresent());
            assertTrue(result.publicEndpoint().isPresent());
            assertTrue(serverTask.get(3, TimeUnit.SECONDS));
            assertTrue(peerTask.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void theRunnerPreservesXqAcrossDnsAndCompletesARealAsciiDidExchange() throws Exception {
        byte[] identity = HexFormat.of().parseHex("464646462d3135383430392d4542444143000000");
        try (var server = socket(); var peer = socket(); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var listening = new CountDownLatch(2);
            var serverTask = workers.submit(() -> {
                listening.countDown();
                var hello = receive(server);
                send(server, hello, 1, PpcsMessages.address((InetSocketAddress) hello.getSocketAddress()));
                var connect = receive(server);
                assertArrayEquals(identity, Arrays.copyOf(PpcsPacket.decode(data(connect)).payload(), 20));
                send(server, connect, 0x40, PpcsMessages.address(endpoint(peer)));
                return true;
            });
            var peerTask = workers.submit(() -> {
                listening.countDown();
                var punch = receive(peer);
                assertArrayEquals(identity, PpcsPacket.decode(data(punch)).payload());
                send(peer, punch, 0x42, identity);
                var alive = receive(peer);
                assertEquals(0xe0, PpcsPacket.decode(data(alive)).type());
                send(peer, alive, 0xe1, new byte[0]);
                return PpcsPacket.decode(data(receive(peer))).type() == 0xf0;
            });
            assertTrue(listening.await(2, TimeUnit.SECONDS), "The local fixture must start before the probe deadline");
            var result = runner().check(new ProbeRequest(DeviceFixtures.DID,
                    List.of(endpoint(server)), Duration.ofSeconds(2), false, TransportProtocol.XQ),
                    () -> false, message -> { });
            assertEquals(ProbeResult.Status.REACHABLE_P2P, result.status(),
                    () -> result.detail() + "; P2P information: " + result.p2pInformation());
            assertEquals(Optional.of(TransportProtocol.XQ), result.p2pInformation().protocol());
            assertEquals(P2pInformation.ConnectionMode.DIRECT_P2P, result.p2pInformation().mode());
            assertEquals(List.of(endpoint(peer)), result.p2pInformation().deviceCandidates());
            assertEquals(List.of(endpoint(server)), result.p2pInformation().respondingServers());
            assertTrue(serverTask.get(3, TimeUnit.SECONDS));
            assertTrue(peerTask.get(3, TimeUnit.SECONDS));
        }
    }

    static DatagramSocket socket() throws Exception {
        var socket = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        socket.setSoTimeout(3000);
        return socket;
    }

    static AvailabilityCheckService runner() {
        return new AvailabilityCheckService(UdpTransport::new, InetAddress::getAllByName, System::nanoTime, UdpSessionProbe::new);
    }

    static InetSocketAddress endpoint(DatagramSocket socket) {
        return new InetSocketAddress(socket.getLocalAddress(), socket.getLocalPort());
    }

    static DatagramPacket receive(DatagramSocket socket) throws Exception {
        var packet = new DatagramPacket(new byte[2048], 2048);
        socket.receive(packet);
        return packet;
    }

    static byte[] data(DatagramPacket packet) {
        return Arrays.copyOf(packet.getData(), packet.getLength());
    }

    static void send(DatagramSocket socket, DatagramPacket request, int type, byte[] payload) throws Exception {
        byte[] data = new PpcsPacket(type, payload).encode();
        socket.send(new DatagramPacket(data, data.length, request.getSocketAddress()));
    }
}
