package com.ivanocj.cameras.availability.infrastructure.probe;

import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.model.YooseeAccount;
import com.ivanocj.cameras.availability.application.port.HostResolver;
import com.ivanocj.cameras.availability.application.port.ProbeRunner;
import com.ivanocj.cameras.availability.domain.model.GwellDeviceId;
import com.ivanocj.cameras.availability.domain.model.IotVideoDeviceId;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class YooseeRemoteOrchestrationTest {
    @ParameterizedTest @EnumSource(value = TransportProtocol.class, names = {"GWELL", "IOTVIDEO"})
    void noReplyRetainsEveryResolvedIpv4PortButNeverReportsOffline(TransportProtocol protocol) throws Exception {
        // Reserve both UDP ports for every local address; macOS cannot bind an unconfigured 127.0.0.2 alias.
        try (var listener = new DatagramSocket(0); var second = new DatagramSocket(0)) {
            int port = listener.getLocalPort();
            var runner = runner(protocol, host -> {
                if (host.equals("bad.invalid")) { throw new IOException(); }
                return new InetAddress[]{InetAddress.getByName("127.0.0.1"), InetAddress.getByName("127.0.0.2"),
                        InetAddress.getByName("127.0.0.1"), InetAddress.getByName("::1")};
            });
            var servers = List.of(InetSocketAddress.createUnresolved("bad.invalid", port),
                    InetSocketAddress.createUnresolved("fixture.invalid", port),
                    InetSocketAddress.createUnresolved("fixture.invalid", second.getLocalPort()));
            var result = runner.check(request(protocol, servers, Duration.ofMillis(350)), () -> false, ignored -> { });
            assertEquals(4, result.p2pInformation().resolvedServers().size());
            assertEquals(ProbeResult.Status.TIMED_OUT, result.status());
            assertFalse(result.serverResponded());
            assertTrue(result.remotePresence().isEmpty());
            assertTrue(result.peer().isEmpty());
        }
    }

    @ParameterizedTest @EnumSource(value = TransportProtocol.class, names = {"GWELL", "IOTVIDEO"})
    void cancellationDuringDnsStopsBeforeListDiscovery(TransportProtocol protocol) throws Exception {
        var resolving = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        var runner = runner(protocol, host -> {
            resolving.countDown();
            try { released.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
        });
        var task = new FutureTask<>(() -> runner.check(request(protocol,
                List.of(InetSocketAddress.createUnresolved("fixture.invalid", 51701)), Duration.ofSeconds(10)),
                cancelled::get, ignored -> { }));
        Thread.ofVirtual().start(task);
        try {
            assertTrue(resolving.await(2, TimeUnit.SECONDS));
            cancelled.set(true);
            var result = task.get(1, TimeUnit.SECONDS);
            assertEquals(ProbeResult.Status.CANCELLED, result.status());
            assertFalse(result.serverResponded());
            assertTrue(result.p2pInformation().localEndpoint().isEmpty());
            assertTrue(result.remotePresence().isEmpty());
        } finally { cancelled.set(true); released.countDown(); }
    }

    private static ProbeRequest request(TransportProtocol protocol, List<InetSocketAddress> servers, Duration timeout) {
        return new ProbeRequest(protocol == TransportProtocol.GWELL ? new GwellDeviceId("01234") : new IotVideoDeviceId("1234"),
                servers, timeout, false, protocol);
    }

    private static ProbeRunner runner(TransportProtocol protocol, HostResolver resolver) {
        if (protocol == TransportProtocol.GWELL) {
            return new GwellRemoteProbe(ignored -> Optional.of(new YooseeAccount.Gwell(new GwellDeviceId("09999"),
                    1, 2, 3, 4, Optional.empty())), resolver, Clock.systemUTC());
        }
        var token = new byte[64]; token[0] = 1;
        return new IotVideoRemoteProbe(ignored -> Optional.of(new YooseeAccount.IotVideo(9999, token, Optional.empty())),
                resolver, Clock.systemUTC());
    }
}
