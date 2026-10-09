package com.ivanocj.cameras.availability.infrastructure.probe;

import com.ivanocj.cameras.availability.application.port.DatagramTransport;
import com.ivanocj.cameras.availability.infrastructure.protocol.GwellDiscoveryMessages;
import com.ivanocj.cameras.availability.testsupport.FakeDatagramTransport;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GwellLanDiscoveryTest {
    private static final InetSocketAddress BROADCAST = new InetSocketAddress("255.255.255.255", 8899);
    private static final InetSocketAddress SOURCE = new InetSocketAddress("192.168.1.8", 8899);

    @Test
    void discoversAndDeduplicatesRepliesAndClosesItsOwnedTransport() throws Exception {
        var transport = new RecordingTransport();
        transport.incoming.add(reply(1234, SOURCE));
        transport.incoming.add(reply(1234, SOURCE));
        transport.incoming.add(reply(0x800004d2, SOURCE));
        var result = discovery(transport).scan(Duration.ofSeconds(2), () -> false, message -> {});
        assertEquals(List.of("1234", "01234"), result.devices().stream().map(device -> device.identifier().value()).toList());
        assertFalse(result.cancelled());
        assertEquals(Duration.ofSeconds(2), result.elapsed());
        assertEquals(Optional.of(transport.localAddress()), result.localEndpoint());
        assertTrue(transport.closed);
        assertEquals(2, transport.destinations.size(), "Discovery retries every second");
        assertTrue(transport.destinations.stream().allMatch(BROADCAST::equals));
        assertTrue(transport.packets.stream().allMatch(bytes -> bytes.length == 1024 && ByteBuffer.wrap(bytes).getInt() == 1));
    }

    @Test
    void ignoresMalformedEchoedAndNonLanPacketsWithoutMakingUpDevices() throws Exception {
        var transport = new RecordingTransport();
        transport.incoming.add(new DatagramTransport.Received(new byte[10], SOURCE));
        transport.incoming.add(new DatagramTransport.Received(GwellDiscoveryMessages.searchRequest(), SOURCE));
        transport.incoming.add(reply(1234, new InetSocketAddress("203.0.113.8", 8899)));
        transport.incoming.add(reply(1234, new InetSocketAddress("::1", 8899)));
        assertEquals(List.of(), discovery(transport).scan(Duration.ofSeconds(1), () -> false, message -> {}).devices());
    }

    @Test
    void retainsPartialDiscoveryOnCancellationButRejectsRepliesReceivedAfterCancellationOrTheDeadline() throws Exception {
        var transport = new RecordingTransport();
        transport.incoming.add(reply(1234, SOURCE));
        transport.incoming.add(reply(5678, SOURCE));
        var result = discovery(transport).scan(Duration.ofSeconds(1), () -> transport.nanos >= 200_000_000L, message -> {});
        assertTrue(result.cancelled());
        assertEquals(List.of("1234"), result.devices().stream().map(device -> device.identifier().value()).toList());
        assertTrue(transport.closed);
        var late = new RecordingTransport();
        late.incoming.add(reply(1234, SOURCE));
        assertTrue(discovery(late).scan(Duration.ofMillis(100), () -> false, message -> {}).devices().isEmpty());
    }

    @Test
    void preCancelledScansNeverOpenSockets() throws Exception {
        var scan = new GwellLanDiscovery(route -> { throw new AssertionError("Must not open a socket"); },
                () -> List.of(BROADCAST), () -> 0L);
        var result = scan.scan(Duration.ofSeconds(1), () -> true, message -> {});
        assertTrue(result.cancelled());
        assertTrue(result.localEndpoint().isEmpty());
    }

    @Test
    void cancellationDuringInterfaceEnumerationStillPreventsSocketSetup() throws Exception {
        var cancelled = new AtomicBoolean();
        var scan = new GwellLanDiscovery(route -> { throw new AssertionError("Cancelled scan must not open a socket"); },
                () -> { cancelled.set(true); return List.of(BROADCAST); }, () -> 0L);
        var result = scan.scan(Duration.ofSeconds(1), cancelled::get, message -> {});
        assertTrue(result.cancelled());
        assertTrue(result.localEndpoint().isEmpty());
    }

    @Test
    void interfaceEnumerationCannotStartNetworkWorkAfterTheDeadline() throws Exception {
        var clock = new RecordingTransport();
        var scan = new GwellLanDiscovery(route -> { throw new AssertionError("Expired scan must not open a socket"); },
                () -> { clock.nanos = Duration.ofSeconds(1).toNanos(); return List.of(BROADCAST); }, () -> clock.nanos);
        var result = scan.scan(Duration.ofSeconds(1), () -> false, message -> {});
        assertFalse(result.cancelled());
        assertTrue(result.localEndpoint().isEmpty());
        assertEquals(Duration.ofSeconds(1), result.elapsed());
    }

    @Test
    void aFailedBroadcastTargetDoesNotPreventDiscoveryOnAnotherInterface() throws Exception {
        var directed = new InetSocketAddress("192.168.1.255", 8899);
        var transport = new RecordingTransport() {
            @Override public void send(byte[] bytes, InetSocketAddress destination) throws IOException {
                if (destination.equals(BROADCAST)) throw new IOException("Fixture network unavailable");
                super.send(bytes, destination);
            }
        };
        transport.incoming.add(reply(1234, SOURCE));
        var scan = new GwellLanDiscovery(route -> transport, () -> List.of(BROADCAST, directed), () -> transport.nanos);
        assertEquals(1, scan.scan(Duration.ofSeconds(1), () -> false, message -> {}).devices().size());
        assertEquals(List.of(directed), transport.destinations);
        assertTrue(transport.closed);
    }

    @Test
    void closesTheSocketWhenAllSendsOrAProgressCallbackFail() {
        var transport = new RecordingTransport() {
            @Override public void send(byte[] bytes, InetSocketAddress destination) throws IOException {
                throw new IOException("Fixture send failed");
            }
        };
        assertThrows(IOException.class, () -> discovery(transport).scan(Duration.ofSeconds(1), () -> false, message -> {}));
        assertTrue(transport.closed);
        var callback = new RecordingTransport();
        assertThrows(IllegalStateException.class, () -> discovery(callback).scan(Duration.ofSeconds(1), () -> false,
                message -> { throw new IllegalStateException("Fixture callback failed"); }));
        assertTrue(callback.closed);
    }

    @Test
    void concurrentScansNeverCompeteForTheFixedListenerPortAndQueuedScansRespectTheirOwnDeadline() throws Exception {
        var receiving = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        var opened = new AtomicInteger();
        var transport = new RecordingTransport() {
            @Override public Optional<Received> receive(Duration wait) {
                receiving.countDown();
                try {
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interruption);
                }
                return Optional.empty();
            }
        };
        var scan = new GwellLanDiscovery(route -> { opened.incrementAndGet(); return transport; },
                () -> List.of(BROADCAST), System::nanoTime);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var active = executor.submit(() -> scan.scan(Duration.ofSeconds(3), cancelled::get, message -> {}));
            try {
                assertTrue(receiving.await(1, TimeUnit.SECONDS));
                var queued = executor.submit(() -> scan.scan(Duration.ofMillis(80), () -> false, message -> {}));
                assertTrue(queued.get(500, TimeUnit.MILLISECONDS).devices().isEmpty());
                assertEquals(1, opened.get());
            } finally {
                cancelled.set(true);
                release.countDown();
            }
            assertTrue(active.get(1, TimeUnit.SECONDS).cancelled());
            assertTrue(transport.closed);
        }
    }

    @Test
    void limitsDiscoverySnapshotsAndMakesTheirCollectionsImmutable() throws Exception {
        var transport = new RecordingTransport() {
            @Override public Optional<Received> receive(Duration wait) {
                nanos += 1_000_000L;
                return Optional.ofNullable(incoming.poll());
            }
        };
        for (int identity = 1; identity <= 300; identity++) {
            transport.incoming.add(reply(identity, SOURCE));
        }
        var result = discovery(transport).scan(Duration.ofSeconds(1), () -> false, message -> {});
        assertEquals(256, result.devices().size());
        assertThrows(UnsupportedOperationException.class, () -> result.devices().clear());
    }

    @Test
    void cancellationWinsOverAnAllFailedSendRound() throws Exception {
        var cancelled = new AtomicBoolean();
        var transport = new RecordingTransport() {
            @Override public void send(byte[] bytes, InetSocketAddress destination) throws IOException {
                cancelled.set(true);
                throw new IOException("Fixture cancelled send");
            }
        };
        assertTrue(discovery(transport).scan(Duration.ofSeconds(1), cancelled::get, message -> {}).cancelled());
        assertTrue(transport.closed);
    }

    private static GwellLanDiscovery discovery(RecordingTransport transport) {
        return new GwellLanDiscovery(route -> transport, () -> List.of(BROADCAST), () -> transport.nanos);
    }

    static DatagramTransport.Received reply(int identity, InetSocketAddress source) {
        return new DatagramTransport.Received(ByteBuffer.allocate(28).putInt(2).putInt(0).putInt(28).putInt(1 << 6)
                .putInt(identity).putInt(7).putInt(3).array(), source);
    }

    private static class RecordingTransport extends FakeDatagramTransport {
        final List<byte[]> packets = new ArrayList<>();
        final List<InetSocketAddress> destinations = new ArrayList<>();
        boolean closed;
        @Override public void send(byte[] bytes, InetSocketAddress destination) throws IOException {
            packets.add(bytes.clone());
            destinations.add(destination);
        }
        @Override public void close() { closed = true; }
    }
}
