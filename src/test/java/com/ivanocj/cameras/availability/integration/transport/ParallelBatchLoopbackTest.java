package com.ivanocj.cameras.availability.integration.transport;

import com.ivanocj.cameras.availability.application.model.BatchResult;
import com.ivanocj.cameras.availability.application.model.P2pInformation;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.model.ServerProfile;
import com.ivanocj.cameras.availability.application.service.BatchAvailabilityService;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.testsupport.TestServerConfigurationProvider;

import static com.ivanocj.cameras.availability.integration.transport.LoopbackProbeTest.data;
import static com.ivanocj.cameras.availability.integration.transport.LoopbackProbeTest.endpoint;
import static com.ivanocj.cameras.availability.integration.transport.LoopbackProbeTest.receive;
import static com.ivanocj.cameras.availability.integration.transport.LoopbackProbeTest.runner;
import static com.ivanocj.cameras.availability.integration.transport.LoopbackProbeTest.send;
import static com.ivanocj.cameras.availability.integration.transport.LoopbackProbeTest.socket;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ivanocj.cameras.availability.infrastructure.protocol.PpcsMessages;
import com.ivanocj.cameras.availability.infrastructure.protocol.PpcsPacket;

import java.io.StringReader;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ParallelBatchLoopbackTest {
    @Test
    void concurrentPpcsAndXqChecksKeepSeparateSocketsIdentitiesResultsAndCleanup() throws Exception {
        byte[] ppcsIdentity = HexFormat.of().parseHex("575757570000000000026ac94542444143000000");
        byte[] xqIdentity = HexFormat.of().parseHex("464646462d3135383430392d4542444143000000");
        try (var ppcsServer = socket(); var ppcsPeer = socket(); var xqServer = socket(); var xqPeer = socket();
             var fixtures = Executors.newVirtualThreadPerTaskExecutor()) {
            var listening = new CountDownLatch(4);
            var bothRendezvous = new CountDownLatch(2);
            var ppcsServerTask = server(fixtures, ppcsServer, ppcsPeer, ppcsIdentity, listening, bothRendezvous);
            var xqServerTask = server(fixtures, xqServer, xqPeer, xqIdentity, listening, bothRendezvous);
            var ppcsPeerTask = peer(fixtures, ppcsPeer, ppcsIdentity, listening);
            var xqPeerTask = peer(fixtures, xqPeer, xqIdentity, listening);
            assertTrue(listening.await(2, TimeUnit.SECONDS));

            var configurations = new TestServerConfigurationProvider(List.of(
                    new ServerProfile("Local PPCS", List.of(endpoint(ppcsServer)), TransportProtocol.PPCS, List.of("WWWW")),
                    new ServerProfile("Local XQ", List.of(endpoint(xqServer)), TransportProtocol.XQ, List.of("FFFF"))));
            var service = new BatchAvailabilityService(runner(), configurations);
            var results = service.check(service.read(new StringReader("WWWW-158409-EBDAC\nFFFF-158409-EBDAC")),
                    Duration.ofSeconds(4), false, 2, () -> false, row -> {}, message -> {});

            assertTrue(results.stream().allMatch(row -> row.state() == BatchResult.State.CHECKED));
            var ppcs = results.get(0).probeResult().orElseThrow();
            var xq = results.get(1).probeResult().orElseThrow();
            assertEquals(ProbeResult.Status.REACHABLE_P2P, ppcs.status(), ppcs.detail());
            assertEquals(ProbeResult.Status.REACHABLE_P2P, xq.status(), xq.detail());
            assertEquals(Optional.of(TransportProtocol.PPCS), ppcs.p2pInformation().protocol());
            assertEquals(Optional.of(TransportProtocol.XQ), xq.p2pInformation().protocol());
            assertEquals(List.of(endpoint(ppcsPeer)), ppcs.p2pInformation().deviceCandidates());
            assertEquals(List.of(endpoint(xqPeer)), xq.p2pInformation().deviceCandidates());
            assertEquals(List.of(endpoint(ppcsServer)), ppcs.p2pInformation().respondingServers());
            assertEquals(List.of(endpoint(xqServer)), xq.p2pInformation().respondingServers());
            assertEquals(P2pInformation.ConnectionMode.DIRECT_P2P, ppcs.p2pInformation().mode());
            assertNotEquals(ppcs.p2pInformation().localEndpoint(), xq.p2pInformation().localEndpoint());
            assertEquals(ppcsServerTask.get(3, TimeUnit.SECONDS), ppcs.publicEndpoint().orElseThrow());
            assertEquals(xqServerTask.get(3, TimeUnit.SECONDS), xq.publicEndpoint().orElseThrow());
            assertTrue(ppcsPeerTask.get(3, TimeUnit.SECONDS), "PPCS session must send close");
            assertTrue(xqPeerTask.get(3, TimeUnit.SECONDS), "XQ session must send close");
        }
    }

    private static Future<InetSocketAddress> server(ExecutorService fixtures, DatagramSocket server, DatagramSocket peer,
                                                   byte[] identity, CountDownLatch listening, CountDownLatch rendezvous) {
        return fixtures.submit(() -> {
            listening.countDown();
            var hello = receive(server);
            assertEquals(0x00, PpcsPacket.decode(data(hello)).type());
            var client = (InetSocketAddress) hello.getSocketAddress();
            send(server, hello, 1, PpcsMessages.address(client));
            var connect = receive(server);
            var request = PpcsPacket.decode(data(connect));
            assertEquals(0x20, request.type());
            assertArrayEquals(identity, Arrays.copyOf(request.payload(), 20));
            rendezvous.countDown();
            assertTrue(rendezvous.await(2, TimeUnit.SECONDS), "Both clients must start before either handshake finishes");
            send(server, connect, 0x21, new byte[4]);
            send(server, connect, 0x40, PpcsMessages.address(endpoint(peer)));
            return client;
        });
    }

    private static Future<Boolean> peer(ExecutorService fixtures, DatagramSocket peer, byte[] identity,
                                         CountDownLatch listening) {
        return fixtures.submit(() -> {
            listening.countDown();
            var punch = receive(peer);
            assertArrayEquals(identity, PpcsPacket.decode(data(punch)).payload());
            send(peer, punch, 0x42, identity);
            var alive = receive(peer);
            assertEquals(0xe0, PpcsPacket.decode(data(alive)).type());
            send(peer, alive, 0xe1, new byte[0]);
            return PpcsPacket.decode(data(receive(peer))).type() == 0xf0;
        });
    }
}
