package com.ivanocj.cameras.availability.integration.transport;

import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.service.GwellAvailabilityService;
import com.ivanocj.cameras.availability.domain.model.GwellDeviceId;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.infrastructure.probe.GwellLanDiscovery;
import com.ivanocj.cameras.availability.infrastructure.transport.UdpTransport;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GwellLoopbackTest {
    @Test
    void aRealUdpDiscoveryReturnsMetadataNotReachabilityAndReleasesItsFixedListenerPort() throws Exception {
        int listenPort;
        try (var reservation = new DatagramSocket(0)) {
            listenPort = reservation.getLocalPort();
        }
        var local = new AtomicReference<InetSocketAddress>();
        try (var device = LoopbackProbeTest.socket(); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var listening = new CountDownLatch(1);
            var fixture = workers.submit(() -> {
                listening.countDown();
                var request = LoopbackProbeTest.receive(device);
                assertEquals(listenPort, request.getPort());
                byte[] bytes = LoopbackProbeTest.data(request);
                assertEquals(1024, bytes.length);
                assertEquals(1, ByteBuffer.wrap(bytes).getInt(0));
                assertEquals(28, ByteBuffer.wrap(bytes).getInt(8));
                device.send(new DatagramPacket(new byte[27], 27, request.getSocketAddress()));
                var reply = ByteBuffer.allocate(96).putInt(2).putInt(0).putInt(28).putInt(1)
                        .putInt(0x800004d2).putInt(7).putInt(3);
                reply.putShort(60, (short) 0x1100).putInt(64, 0x55443322).putInt(68, 42);
                for (int index = 0; index < 2; index++) {
                    device.send(new DatagramPacket(reply.array(), reply.capacity(), request.getSocketAddress()));
                }
                return true;
            });
            assertTrue(listening.await(1, TimeUnit.SECONDS));
            var discovery = new GwellLanDiscovery(route -> {
                var transport = new UdpTransport(route, listenPort);
                local.set(transport.localAddress());
                return transport;
            }, () -> List.of(LoopbackProbeTest.endpoint(device)), System::nanoTime);
            var service = new GwellAvailabilityService(discovery, (request, cancelled, progress) -> {
                throw new AssertionError("LAN-only request must not open a remote session");
            });
            var result = service.check(new ProbeRequest(new GwellDeviceId("01234"), List.of(), Duration.ofSeconds(1),
                    true, TransportProtocol.GWELL), () -> false, message -> {});
            assertEquals(ProbeResult.Status.DISCOVERED_LAN, result.status(), result::detail);
            assertEquals(1, result.lanDevices().size());
            assertEquals("00:11:22:33:44:55", result.lanDevices().getFirst().macAddress().orElseThrow());
            assertEquals(42, result.lanDevices().getFirst().model().orElseThrow());
            assertTrue(result.peer().isEmpty());
            assertFalse(result.serverResponded());
            assertTrue(fixture.get(2, TimeUnit.SECONDS));
        }
        try (var reopened = new DatagramSocket(new InetSocketAddress("0.0.0.0", listenPort))) {
            assertEquals(local.get().getPort(), reopened.getLocalPort());
        }
    }
}
