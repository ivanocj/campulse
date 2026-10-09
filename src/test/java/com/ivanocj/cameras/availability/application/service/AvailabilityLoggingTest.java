package com.ivanocj.cameras.availability.application.service;

import com.ivanocj.cameras.availability.application.logging.LogContext;
import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.testsupport.DeviceFixtures;
import com.ivanocj.cameras.availability.testsupport.FakeDatagramTransport;
import com.ivanocj.cameras.availability.testsupport.LogCapture;
import com.ivanocj.cameras.availability.testsupport.TestServerConfigurationProvider;
import com.ivanocj.cameras.availability.testsupport.TestSessionProbes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class AvailabilityLoggingTest {
    @Test
    void logsStartAndTerminalOutcomeWithTheSameProbeIdAndCarriesItIntoDns() throws Exception {
        var dnsContext = new AtomicReference<Map<String, String>>();
        var transport = new FakeDatagramTransport();
        var runner = new AvailabilityCheckService(route -> transport, host -> {
            dnsContext.set(LogContext.snapshot());
            return new InetAddress[] {InetAddress.getLoopbackAddress()};
        }, () -> transport.nanos, TestSessionProbes.NO_REPLY);
        try (var logs = new LogCapture()) {
            var result = runner.check(request(), () -> false, message -> {});
            assertEquals(ProbeResult.Status.INCONCLUSIVE, result.status());
            var start = logs.events("probe_started").getFirst();
            var completed = logs.events("probe_completed").getFirst();
            var probe = start.getMDCPropertyMap().get("probeId");
            assertEquals(probe, UUID.fromString(probe).toString());
            assertEquals(probe, completed.getMDCPropertyMap().get("probeId"));
            assertEquals(probe, dnsContext.get().get("probeId"));
            assertEquals(Level.INFO, completed.getLevel());
            assertTrue(completed.getFormattedMessage().contains("status=INCONCLUSIVE"));
            assertNull(MDC.get("probeId"));
            assertNoDeviceData(logs);
        }
    }

    @Test
    void localFailuresAreLoggedAsErrorsWithoutChangingTheirPropagationOrExposingTheirMessage() {
        var failure = new IOException("FFFF-158409-EBDAC 198.51.100.1 sensitive data");
        var runner = new AvailabilityCheckService(route -> { throw failure; },
                host -> new InetAddress[] {InetAddress.getLoopbackAddress()}, System::nanoTime, TestSessionProbes.NO_REPLY);
        try (var logs = new LogCapture()) {
            assertSame(failure, assertThrows(IOException.class, () -> runner.check(request(), () -> false, message -> {})));
            var event = logs.events("probe_failed").getFirst();
            assertEquals(Level.ERROR, event.getLevel());
            assertEquals(IOException.class.getName(), event.getThrowableProxy().getClassName());
            assertNull(MDC.get("probeId"));
            assertNoDeviceData(logs);
        }
    }

    @Test
    void cancellationIsAnExpectedInfoOutcomeNotAnApplicationError() throws Exception {
        var runner = new AvailabilityCheckService(route -> { throw new AssertionError("No socket should open"); },
                host -> { throw new AssertionError("No DNS should run"); }, System::nanoTime, TestSessionProbes.NO_REPLY);
        try (var logs = new LogCapture()) {
            assertEquals(ProbeResult.Status.CANCELLED, runner.check(request(), () -> true, message -> {}).status());
            var completed = logs.events("probe_completed").getFirst();
            assertEquals(Level.INFO, completed.getLevel());
            assertTrue(completed.getFormattedMessage().contains("status=CANCELLED"));
            assertTrue(logs.events("probe_failed").isEmpty());
        }
    }

    @Test
    void parallelRowsShareOneBatchIdButHaveIndependentProbeIdsAndNoPersistedSerialDescriptionsOrAddresses() throws Exception {
        var entered = new CountDownLatch(8);
        var release = new CountDownLatch(1);
        var contexts = new ConcurrentHashMap<String, Map<String, String>>();
        var service = new BatchAvailabilityService((request, token, progress) -> {
            contexts.put(request.did().value(), LogContext.snapshot());
            entered.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) { throw new IOException("Test did not release the probes"); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            return new ProbeResult(ProbeResult.Status.TIMED_OUT, "198.51.100.1 sensitive data", false,
                    java.util.Optional.empty(), java.util.Optional.empty(), Duration.ofMillis(100));
        }, new TestServerConfigurationProvider());
        var text = new StringBuilder("Serial,Description\n");
        for (int serial = 1; serial <= 20; serial++) { text.append("FFFF-").append(serial).append("-A,sensitive data\n"); }
        var entries = service.readCsv(new StringReader(text.toString()));
        try (var logs = new LogCapture(); var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = worker.submit(() -> service.check(entries, Duration.ofSeconds(2), false, 8,
                    () -> false, row -> {}, message -> {}));
            try { assertTrue(entered.await(2, TimeUnit.SECONDS)); }
            finally { release.countDown(); }
            assertEquals(20, result.get(3, TimeUnit.SECONDS).size());
            assertEquals(1, contexts.values().stream().map(map -> map.get("batchId")).distinct().count());
            assertEquals(20, contexts.values().stream().map(map -> map.get("probeId")).distinct().count());
            var started = logs.events("batch_started").getFirst();
            var completed = logs.events("batch_completed").getFirst();
            assertEquals(started.getMDCPropertyMap().get("batchId"), completed.getMDCPropertyMap().get("batchId"));
            assertTrue(started.getFormattedMessage().contains("parallelism=8"));
            assertTrue(completed.getFormattedMessage().contains("checked=20"));
            assertNoDeviceData(logs);
        }
    }

    private static ProbeRequest request() {
        return new ProbeRequest(DeviceFixtures.DID, java.util.List.of(InetSocketAddress.createUnresolved("private.example", 32100)),
                Duration.ofSeconds(1), false);
    }

    private static void assertNoDeviceData(LogCapture logs) {
        for (var event : logs.events()) {
            var text = event.getFormattedMessage();
            for (var sensitive : new String[] {"FFFF-", "198.51.100.1", "private.example", "sensitive data"}) {
                assertFalse(text.contains(sensitive), text);
            }
        }
    }
}
