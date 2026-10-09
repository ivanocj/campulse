package com.ivanocj.cameras.availability.application.service;

import com.ivanocj.cameras.availability.application.model.P2pInformation;
import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.model.SessionRequest;
import com.ivanocj.cameras.availability.application.port.SessionProbeFactory;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.testsupport.DeviceFixtures;
import com.ivanocj.cameras.availability.testsupport.FakeDatagramTransport;
import com.ivanocj.cameras.availability.testsupport.TestSessionProbes;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AvailabilityCheckServiceTest {
    @Test
    void ownsAndClosesTheSocketForEachCompletedCheck() throws Exception {
        var transport = new FakeDatagramTransport() {
            @Override
            public void close() {
                closed = true;
            }
            boolean closed;
        };
        var runner = new AvailabilityCheckService(route -> transport,
                host -> new InetAddress[] {InetAddress.getLoopbackAddress()}, () -> transport.nanos, TestSessionProbes.NO_REPLY);
        runner.check(new ProbeRequest(DeviceFixtures.DID,
                        List.of(InetSocketAddress.createUnresolved("server.example", 32100)),
                        Duration.ofSeconds(1), false), () -> false, message -> { });
        assertTrue(transport.closed);
    }

    @Test
    void oneUnresolvableServerDoesNotPreventTryingAnotherServer() throws Exception {
        var transport = new FakeDatagramTransport();
        var runner = new AvailabilityCheckService(route -> transport, host -> {
            if (host.equals("bad.example")) {
                throw new UnknownHostException(host);
            }
            return new InetAddress[] {InetAddress.getLoopbackAddress()};
        }, () -> transport.nanos, TestSessionProbes.NO_REPLY);
        assertEquals(ProbeResult.Status.INCONCLUSIVE, runner.check(new ProbeRequest(DeviceFixtures.DID,
                List.of(InetSocketAddress.createUnresolved("bad.example", 32100),
                        InetSocketAddress.createUnresolved("good.example", 32100)), Duration.ofSeconds(1), false),
                () -> false, message -> { }).status());
    }

    @Test
    void cancellationDoesNotWaitForABlockedDnsResolver() throws Exception {
        var resolving = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        var runner = new AvailabilityCheckService(route -> new FakeDatagramTransport(), host -> {
            resolving.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            return new InetAddress[] {InetAddress.getLoopbackAddress()};
        }, System::nanoTime, TestSessionProbes.NO_REPLY);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = workers.submit(() -> runner.check(new ProbeRequest(DeviceFixtures.DID,
                    List.of(InetSocketAddress.createUnresolved("slow.example", 32100)), Duration.ofSeconds(2), false),
                    cancelled::get, message -> { }));
            assertTrue(resolving.await(1, TimeUnit.SECONDS));
            cancelled.set(true);
            try {
                assertEquals(ProbeResult.Status.CANCELLED, result.get(500, TimeUnit.MILLISECONDS).status());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void theOverallDeadlineAlsoBoundsDnsResolution() throws Exception {
        var release = new CountDownLatch(1);
        var runner = new AvailabilityCheckService(route -> new FakeDatagramTransport(), host -> {
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            return new InetAddress[] {InetAddress.getLoopbackAddress()};
        }, System::nanoTime, TestSessionProbes.NO_REPLY);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = workers.submit(() -> runner.check(new ProbeRequest(DeviceFixtures.DID,
                    List.of(InetSocketAddress.createUnresolved("slow.example", 32100)), Duration.ofMillis(80), false),
                    () -> false, message -> { }));
            try {
                assertEquals(ProbeResult.Status.INCONCLUSIVE, result.get(500, TimeUnit.MILLISECONDS).status());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void resolvesAllServersConcurrently() throws Exception {
        var bothInFlight = new CountDownLatch(2);
        var overlapped = new AtomicBoolean(true);
        var runner = new AvailabilityCheckService(route -> new FakeDatagramTransport(), host -> {
            bothInFlight.countDown();
            try {
                overlapped.compareAndSet(true, bothInFlight.await(1, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            return new InetAddress[] {InetAddress.getLoopbackAddress()};
        }, System::nanoTime, TestSessionProbes.NO_REPLY);
        runner.check(new ProbeRequest(DeviceFixtures.DID, List.of(InetSocketAddress.createUnresolved("a.example", 32100),
                InetSocketAddress.createUnresolved("b.example", 32100)), Duration.ofSeconds(4), false),
                () -> false, message -> { });
        assertTrue(overlapped.get());
    }

    @Test
    void aSlowServerLookupDoesNotStopTheCheckUsingTheServersThatResolved() throws Exception {
        var release = new CountDownLatch(1);
        var session = new AtomicReference<SessionRequest>();
        var progress = new CopyOnWriteArrayList<String>();
        var runner = new AvailabilityCheckService(route -> new FakeDatagramTransport(), host -> {
            if (host.equals("slow.example")) {
                try {
                    release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
            return new InetAddress[] {InetAddress.getLoopbackAddress()};
        }, System::nanoTime, capturing(session));
        try {
            runner.check(new ProbeRequest(DeviceFixtures.DID, List.of(InetSocketAddress.createUnresolved("slow.example", 32100),
                    InetSocketAddress.createUnresolved("fast.example", 32100)), Duration.ofMillis(400), false),
                    () -> false, progress::add);
        } finally {
            release.countDown();
        }
        assertEquals(List.of(new InetSocketAddress(InetAddress.getLoopbackAddress(), 32100)), session.get().servers());
        assertTrue(progress.contains("Resolving slow.example took too long; skipped."));
    }

    @Test
    void explainsWhyAnIpv6OnlyServerWasSkipped() throws Exception {
        var progress = new ArrayList<String>();
        var runner = new AvailabilityCheckService(route -> new FakeDatagramTransport(), host ->
                host.equals("v6.example") ? new InetAddress[] {InetAddress.getByName("::1")}
                        : new InetAddress[] {InetAddress.getLoopbackAddress()},
                () -> 0L, TestSessionProbes.NO_REPLY);
        runner.check(new ProbeRequest(DeviceFixtures.DID, List.of(InetSocketAddress.createUnresolved("v6.example", 32100),
                InetSocketAddress.createUnresolved("v4.example", 32100)), Duration.ofSeconds(1), false),
                () -> false, progress::add);
        assertTrue(progress.contains("v6.example has no IPv4 address; skipped."));
    }

    @Test
    void describesAResolverFailureThatHasNoMessage() throws Exception {
        var progress = new ArrayList<String>();
        var runner = new AvailabilityCheckService(route -> new FakeDatagramTransport(), host -> {
            if (host.equals("bad.example")) {
                throw new IOException();
            }
            return new InetAddress[] {InetAddress.getLoopbackAddress()};
        }, () -> 0L, TestSessionProbes.NO_REPLY);
        runner.check(new ProbeRequest(DeviceFixtures.DID, List.of(InetSocketAddress.createUnresolved("bad.example", 32100),
                InetSocketAddress.createUnresolved("good.example", 32100)), Duration.ofSeconds(1), false),
                () -> false, progress::add);
        assertTrue(progress.contains("Could not resolve bad.example: IOException"));
    }

    @Test
    void namesTheSelectedTransportWhenNoServerResolves() {
        var runner = new AvailabilityCheckService(route -> new FakeDatagramTransport(), host -> {
            throw new UnknownHostException(host);
        }, () -> 0L, TestSessionProbes.NO_REPLY);
        var error = assertThrows(IOException.class, () -> runner.check(new ProbeRequest(DeviceFixtures.DID,
                List.of(InetSocketAddress.createUnresolved("bad.example", 32100)), Duration.ofSeconds(1), false,
                TransportProtocol.XQ), () -> false, message -> { }));
        assertEquals("Could not resolve any XQ IPv4 server.", error.getMessage());
    }

    @Test
    void lanOnlyChecksDoNotNeedServersOrDns() throws Exception {
        var session = new AtomicReference<SessionRequest>();
        var route = new AtomicReference<InetSocketAddress>();
        var runner = new AvailabilityCheckService(destination -> {
            route.set(destination);
            return new FakeDatagramTransport();
        }, host -> { throw new AssertionError("LAN-only checks must not use DNS"); }, () -> 0L, capturing(session));
        runner.check(new ProbeRequest(DeviceFixtures.DID, List.of(), Duration.ofSeconds(1), true),
                () -> false, message -> {});
        assertEquals(List.of(), session.get().servers());
        assertTrue(session.get().lanSearch());
        assertEquals(new InetSocketAddress("255.255.255.255", 32108), route.get());
    }

    @Test
    void lanChecksStillRunWhenEveryDnsLookupFails() throws Exception {
        var session = new AtomicReference<SessionRequest>();
        var runner = new AvailabilityCheckService(destination -> new FakeDatagramTransport(),
                host -> { throw new UnknownHostException(host); }, () -> 0L, capturing(session));
        runner.check(new ProbeRequest(DeviceFixtures.DID,
                List.of(InetSocketAddress.createUnresolved("bad.example", 32100)), Duration.ofSeconds(1), true),
                () -> false, message -> {});
        assertEquals(List.of(), session.get().servers());
        assertTrue(session.get().lanSearch());
    }

    @Test
    void lanChecksGetTheRemainingBudgetEvenWhenDnsTimesOut() throws Exception {
        var release = new CountDownLatch(1);
        var session = new AtomicReference<SessionRequest>();
        var runner = new AvailabilityCheckService(destination -> new FakeDatagramTransport(), host -> {
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            return new InetAddress[] {InetAddress.getLoopbackAddress()};
        }, System::nanoTime, capturing(session));
        try {
            runner.check(new ProbeRequest(DeviceFixtures.DID,
                    List.of(InetSocketAddress.createUnresolved("slow.example", 32100)), Duration.ofMillis(300), true),
                    () -> false, message -> {});
            assertEquals(List.of(), session.get().servers());
            assertTrue(session.get().timeout().isPositive());
        } finally {
            release.countDown();
        }
    }

    @Test
    void cancellingDuringTheLanFallbackDiagnosticDoesNotOpenASocket() throws Exception {
        var cancelled = new AtomicBoolean();
        var runner = new AvailabilityCheckService(destination -> {
            throw new AssertionError("Cancellation must prevent LAN socket setup");
        }, host -> { throw new AssertionError("LAN-only checks must not use DNS"); }, () -> 0L, TestSessionProbes.NO_REPLY);
        var result = runner.check(new ProbeRequest(DeviceFixtures.DID, List.of(), Duration.ofSeconds(1), true),
                cancelled::get, message -> cancelled.set(true));
        assertEquals(ProbeResult.Status.CANCELLED, result.status());
        assertTrue(result.p2pInformation().localEndpoint().isEmpty());
    }

    @Test
    void timeSpentInDnsDiagnosticsCannotStartLanWorkAfterTheOverallDeadline() throws Exception {
        var clock = new FakeDatagramTransport();
        var runner = new AvailabilityCheckService(destination -> {
            throw new AssertionError("Expired checks must not open a LAN socket");
        }, host -> { throw new UnknownHostException(host); }, () -> clock.nanos, TestSessionProbes.NO_REPLY);
        var result = runner.check(new ProbeRequest(DeviceFixtures.DID,
                List.of(InetSocketAddress.createUnresolved("bad.example", 32100)), Duration.ofSeconds(1), true),
                () -> false, message -> clock.nanos = Duration.ofSeconds(1).toNanos());
        assertEquals(ProbeResult.Status.INCONCLUSIVE, result.status());
        assertEquals(Duration.ofSeconds(1), result.elapsed());
        assertTrue(result.p2pInformation().localEndpoint().isEmpty());
    }

    @Test
    void namesThatResolveToTheSameAddressAreProbedOnce() throws Exception {
        var session = new AtomicReference<SessionRequest>();
        var runner = new AvailabilityCheckService(route -> new FakeDatagramTransport(),
                host -> new InetAddress[] {InetAddress.getLoopbackAddress()}, () -> 0L, capturing(session));
        runner.check(new ProbeRequest(DeviceFixtures.DID, List.of(InetSocketAddress.createUnresolved("one.example", 32100),
                InetSocketAddress.createUnresolved("two.example", 32100)), Duration.ofSeconds(1), false),
                () -> false, message -> { });
        assertEquals(1, session.get().servers().size());
    }

    @Test
    void retainsEveryDistinctIpv4AddressAndPortInsteadOfOnlyTheFirstDnsAnswer() throws Exception {
        var session = new AtomicReference<SessionRequest>();
        var first = InetAddress.getByName("192.0.2.1");
        var second = InetAddress.getByName("192.0.2.2");
        var runner = new AvailabilityCheckService(route -> new FakeDatagramTransport(),
                host -> new InetAddress[] {InetAddress.getByName("::1"), first, second, first},
                () -> 0L, capturing(session));
        runner.check(new ProbeRequest(DeviceFixtures.DID,
                List.of(InetSocketAddress.createUnresolved("pool.example", 32100),
                        InetSocketAddress.createUnresolved("pool.example", 32101)), Duration.ofSeconds(1), false),
                () -> false, message -> {});
        assertEquals(List.of(new InetSocketAddress(first, 32100), new InetSocketAddress(second, 32100),
                new InetSocketAddress(first, 32101), new InetSocketAddress(second, 32101)), session.get().servers());
    }

    private static SessionProbeFactory capturing(AtomicReference<SessionRequest> session) {
        return (transport, clock) -> (request, cancelled, progress) -> {
            session.set(request);
            return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
        };
    }

    @Test
    void reportedElapsedTimeIncludesServerResolution() throws Exception {
        var transport = new FakeDatagramTransport();
        var runner = new AvailabilityCheckService(route -> transport, host -> {
            transport.nanos = Duration.ofMillis(250).toNanos();
            return new InetAddress[] {InetAddress.getLoopbackAddress()};
        }, () -> transport.nanos, TestSessionProbes.NO_REPLY);
        var result = runner.check(new ProbeRequest(DeviceFixtures.DID,
                List.of(InetSocketAddress.createUnresolved("server.example", 32100)), Duration.ofSeconds(1), false),
                () -> false, message -> { });
        assertEquals(Duration.ofSeconds(1), result.elapsed());
    }

    @Test
    void adjustingTheOverallElapsedTimeDoesNotDiscardTheSessionsP2pInformation() throws Exception {
        var transport = new FakeDatagramTransport();
        var information = new P2pInformation(Optional.of(TransportProtocol.XQ), P2pInformation.ConnectionMode.NONE,
                Optional.of(transport.localAddress()), List.of(DeviceFixtures.SERVER), List.of(DeviceFixtures.SERVER),
                List.of(DeviceFixtures.PEER), List.of(), Map.of(DeviceFixtures.SERVER, 0));
        var runner = new AvailabilityCheckService(route -> transport, host -> {
            transport.nanos = Duration.ofMillis(250).toNanos();
            return new InetAddress[] {InetAddress.getLoopbackAddress()};
        }, () -> transport.nanos, (socket, clock) -> (session, cancelled, progress) -> {
            transport.nanos += session.timeout().toNanos();
            return new ProbeResult(ProbeResult.Status.TIMED_OUT, "No alive acknowledgement.", true,
                    Optional.empty(), Optional.empty(), session.timeout(), information);
        });
        var result = runner.check(new ProbeRequest(DeviceFixtures.DID,
                List.of(InetSocketAddress.createUnresolved("server.example", 32100)), Duration.ofSeconds(1), false,
                TransportProtocol.XQ), () -> false, message -> {});
        assertEquals(Duration.ofSeconds(1), result.elapsed());
        assertSame(information, result.p2pInformation());
    }

    @Test
    void cancellationBeforeDnsStillIdentifiesTheRequestedProtocolWithoutInventingEndpoints() throws Exception {
        var runner = new AvailabilityCheckService(route -> {
            throw new AssertionError("Cancellation must not open a socket");
        }, host -> {
            throw new AssertionError("Cancellation must not resolve servers");
        }, () -> 0L, TestSessionProbes.NO_REPLY);
        var result = runner.check(new ProbeRequest(DeviceFixtures.DID,
                List.of(InetSocketAddress.createUnresolved("server.example", 32100)), Duration.ofSeconds(1), false,
                TransportProtocol.XQ), () -> true, message -> {});
        assertEquals(Optional.of(TransportProtocol.XQ), result.p2pInformation().protocol());
        assertTrue(result.p2pInformation().localEndpoint().isEmpty());
        assertEquals(List.of(), result.p2pInformation().resolvedServers());
        assertEquals(List.of(), result.p2pInformation().deviceCandidates());
    }
}
