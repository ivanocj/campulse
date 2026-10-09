package com.ivanocj.cameras.availability.integration.transport;

import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.port.DatagramTransport;
import com.ivanocj.cameras.availability.application.service.AvailabilityCheckService;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.infrastructure.probe.UdpSessionProbe;
import com.ivanocj.cameras.availability.infrastructure.protocol.PpcsPacket;
import com.ivanocj.cameras.availability.infrastructure.protocol.ProtocolMessages;
import com.ivanocj.cameras.availability.infrastructure.transport.UdpTransport;
import com.ivanocj.cameras.availability.testsupport.DeviceFixtures;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class LanOnlyLoopbackTest {
    @ParameterizedTest
    @EnumSource(value = TransportProtocol.class, names = {"PPCS", "XQ"})
    void lanOnlyAndFailedDnsChecksStillConfirmAndCloseAMatchingPeer(TransportProtocol protocol) throws Exception {
        for (boolean badDns : new boolean[] {false, true}) {
            try (var device = LoopbackProbeTest.socket(); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                byte[] identity = new ProtocolMessages(protocol).identity(DeviceFixtures.DID);
                var listening = new CountDownLatch(1);
                var fixture = workers.submit(() -> {
                    listening.countDown();
                    var search = LoopbackProbeTest.receive(device);
                    assertEquals(0x30, PpcsPacket.decode(LoopbackProbeTest.data(search)).type());
                    LoopbackProbeTest.send(device, search, 0x41, identity);
                    assertArrayEquals(identity, PpcsPacket.decode(LoopbackProbeTest.data(LoopbackProbeTest.receive(device))).payload());
                    LoopbackProbeTest.send(device, search, 0x42, identity);
                    var alive = LoopbackProbeTest.receive(device);
                    assertEquals(0xe0, PpcsPacket.decode(LoopbackProbeTest.data(alive)).type());
                    LoopbackProbeTest.send(device, alive, 0xe1, new byte[0]);
                    return PpcsPacket.decode(LoopbackProbeTest.data(LoopbackProbeTest.receive(device))).type() == 0xf0;
                });
                assertTrue(listening.await(1, TimeUnit.SECONDS));
                var runner = new AvailabilityCheckService(route -> new RedirectedLanTransport(
                        new UdpTransport(LoopbackProbeTest.endpoint(device)), LoopbackProbeTest.endpoint(device)), host -> {
                    if (!badDns) throw new AssertionError("LAN-only check must not resolve a server");
                    throw new UnknownHostException(host);
                }, System::nanoTime, UdpSessionProbe::new);
                var result = runner.check(new ProbeRequest(DeviceFixtures.DID,
                        badDns ? List.of(InetSocketAddress.createUnresolved("missing.example", 32100)) : List.of(),
                        Duration.ofSeconds(2), true, protocol), () -> false, message -> {});
                assertEquals(ProbeResult.Status.REACHABLE_P2P, result.status(), result::detail);
                assertEquals(LoopbackProbeTest.endpoint(device), result.peer().orElseThrow());
                assertTrue(result.p2pInformation().resolvedServers().isEmpty());
                assertFalse(result.serverResponded());
                assertTrue(fixture.get(3, TimeUnit.SECONDS));
            }
        }
    }

    /** Redirects only the discovery broadcast to loopback; tests never broadcast to real cameras. */
    private record RedirectedLanTransport(DatagramTransport delegate, InetSocketAddress target) implements DatagramTransport {
        @Override public void send(byte[] bytes, InetSocketAddress destination) throws IOException {
            delegate.send(bytes, destination.equals(new InetSocketAddress("255.255.255.255", 32108)) ? target : destination);
        }
        @Override public Optional<Received> receive(Duration wait) throws IOException { return delegate.receive(wait); }
        @Override public InetSocketAddress localAddress() { return delegate.localAddress(); }
        @Override public void close() { delegate.close(); }
    }
}
