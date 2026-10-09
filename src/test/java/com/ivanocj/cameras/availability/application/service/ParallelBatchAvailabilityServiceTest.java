package com.ivanocj.cameras.availability.application.service;

import com.ivanocj.cameras.availability.application.model.BatchResult;
import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.port.ProbeRunner;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.testsupport.TestServerConfigurationProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ParallelBatchAvailabilityServiceTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(7);

    @Test
    void limitsActiveProbesAndRefillsAFreedSlotWithoutWaitingForEarlierSlowRows() throws Exception {
        var firstWave = new CountDownLatch(3);
        var thirdCanFinish = new CountDownLatch(1);
        var releaseSlow = new CountDownLatch(1);
        var fourthEntered = new CountDownLatch(1);
        var published = new CountDownLatch(1);
        var active = new AtomicInteger();
        var maximum = new AtomicInteger();
        var calls = new CopyOnWriteArrayList<ProbeRequest>();
        var updates = new ArrayList<BatchResult>();
        var coordinator = new AtomicReference<Thread>();
        var service = service((request, cancelled, progress) -> {
            calls.add(request);
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                progress.accept("Hello received.");
                switch (request.did().value()) {
                    case "FFFF-1-A", "WWWW-2-B" -> { firstWave.countDown(); await(releaseSlow); }
                    case "TTTT-3-C" -> { firstWave.countDown(); await(thirdCanFinish); }
                    default -> { fourthEntered.countDown(); await(releaseSlow); }
                }
                return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
            } finally {
                active.decrementAndGet();
            }
        });
        var entries = service.read(new StringReader("FFFF-1-A\nWWWW-2-B\nTTTT-3-C\nGGGG-4-D\nFFFF-5-E"));
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = worker.submit(() -> {
                coordinator.set(Thread.currentThread());
                return service.check(entries, TIMEOUT, false, 3, () -> false, update -> {
                    assertEquals(coordinator.get(), Thread.currentThread(), "Completion callbacks must be serialized");
                    updates.add(update);
                    published.countDown();
                }, message -> assertEquals(coordinator.get(), Thread.currentThread(), "Progress callbacks must be serialized"));
            });
            try {
                assertTrue(firstWave.await(2, TimeUnit.SECONDS), "Three probes should overlap");
                assertEquals(3, calls.size());
                thirdCanFinish.countDown();
                assertTrue(published.await(2, TimeUnit.SECONDS), "A later row must publish while earlier rows are blocked");
                assertTrue(fourthEntered.await(2, TimeUnit.SECONDS), "The freed slot should start another device");
                assertEquals(4, calls.size());
                assertFalse(result.isDone());
            } finally {
                thirdCanFinish.countDown();
                releaseSlow.countDown();
            }
            var results = result.get(3, TimeUnit.SECONDS);
            assertEquals(entries, results.stream().map(BatchResult::entry).toList(), "Final results retain source order");
            assertEquals(3, maximum.get());
            assertEquals(0, active.get());
            assertEquals("TTTT-3-C", updates.getFirst().entry().code());
            assertEquals(5, updates.size());
            assertTrue(calls.stream().allMatch(request -> TIMEOUT.equals(request.timeout()) && !request.lanSearch()));
            assertEquals(TransportProtocol.PPCS, calls.stream().filter(request -> request.did().prefix().equals("WWWW"))
                    .findFirst().orElseThrow().protocol());
            assertEquals(TransportProtocol.XQ, calls.stream().filter(request -> request.did().prefix().equals("FFFF"))
                    .findFirst().orElseThrow().protocol());
        }
    }

    @Test
    void repeatedDidsNeverOverlapButDoNotPreventOtherDevicesFromUsingThePool() throws Exception {
        var firstAndOther = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var duplicateCalls = new AtomicInteger();
        var duplicateActive = new AtomicInteger();
        var service = service((request, cancelled, progress) -> {
            if (request.did().prefix().equals("FFFF")) {
                assertEquals(1, duplicateActive.incrementAndGet(), "A repeated DID must own only one active session");
                int call = duplicateCalls.incrementAndGet();
                try {
                    if (call == 1) {
                        firstAndOther.countDown();
                        await(release);
                    }
                    return new ProbeResult(call == 1 ? ProbeResult.Status.TIMED_OUT : ProbeResult.Status.REACHABLE_P2P);
                } finally {
                    duplicateActive.decrementAndGet();
                }
            }
            firstAndOther.countDown();
            await(release);
            return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
        });
        var entries = service.readCsv(new StringReader("Serial,Description\nFFFF-1-A,First\nFFFF-1-A,Second\nWWWW-2-B,Other"));
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = worker.submit(() -> service.check(entries, TIMEOUT, true, 3,
                    () -> false, update -> {}, message -> {}));
            try {
                assertTrue(firstAndOther.await(2, TimeUnit.SECONDS), "Duplicate rows must not block unrelated DIDs");
                assertEquals(1, duplicateCalls.get());
            } finally {
                release.countDown();
            }
            var results = result.get(3, TimeUnit.SECONDS);
            assertEquals(List.of("First", "Second", "Other"), results.stream().map(row -> row.entry().description()).toList());
            assertEquals(ProbeResult.Status.TIMED_OUT, results.get(0).probeResult().orElseThrow().status());
            assertEquals(ProbeResult.Status.REACHABLE_P2P, results.get(1).probeResult().orElseThrow().status());
            assertEquals(2, duplicateCalls.get());
        }
    }

    @Test
    void ppcsLeadingZeroAliasesShareASessionSlotWithoutChangingImportedSerials() throws Exception {
        var firstAndOther = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var aliasCalls = new AtomicInteger();
        var aliasActive = new AtomicInteger();
        var service = service((request, cancelled, progress) -> {
            if (request.protocol() == TransportProtocol.PPCS) {
                assertEquals(1, aliasActive.incrementAndGet(), "PPCS serial padding does not change the binary identity");
                int call = aliasCalls.incrementAndGet();
                try {
                    if (call == 1) {
                        firstAndOther.countDown();
                        await(release);
                    }
                    return new ProbeResult(ProbeResult.Status.TIMED_OUT);
                } finally {
                    aliasActive.decrementAndGet();
                }
            }
            firstAndOther.countDown();
            await(release);
            return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
        });
        var entries = service.read(new StringReader("WWWW-0001-A\nWWWW-1-A\nFFFF-2-B"));
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = worker.submit(() -> service.check(entries, TIMEOUT, true, 3,
                    () -> false, row -> {}, message -> {}));
            try {
                assertTrue(firstAndOther.await(2, TimeUnit.SECONDS));
                assertEquals(1, aliasCalls.get());
            } finally {
                release.countDown();
            }
            var results = result.get(3, TimeUnit.SECONDS);
            assertEquals(List.of("WWWW-0001-A", "WWWW-1-A", "FFFF-2-B"),
                    results.stream().map(row -> row.entry().code()).toList());
            assertEquals(2, aliasCalls.get());
        }
    }

    @Test
    void xqSerialStringsWithDifferentZeroPaddingRemainDistinctAsciiIdentities() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var service = service((request, cancelled, progress) -> {
            entered.countDown();
            await(release);
            return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
        });
        var entries = service.read(new StringReader("FFFF-0001-A\nFFFF-1-A"));
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = worker.submit(() -> service.check(entries, TIMEOUT, false, 2,
                    () -> false, row -> {}, message -> {}));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS), "XQ uses the full ASCII serial, unlike PPCS");
            } finally {
                release.countDown();
            }
            assertEquals(entries, result.get(3, TimeUnit.SECONDS).stream().map(BatchResult::entry).toList());
        }
    }

    @Test
    void cancellationStopsNewDevicesAndWaitsForEveryActiveProbesCleanupWithoutInterruptingThem() throws Exception {
        var entered = new CountDownLatch(3);
        var checkToken = new CountDownLatch(1);
        var observed = new CountDownLatch(3);
        var cleanup = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        var calls = new AtomicInteger();
        var service = service((request, token, progress) -> {
            calls.incrementAndGet();
            entered.countDown();
            await(checkToken);
            assertTrue(token.getAsBoolean());
            observed.countDown();
            await(cleanup);
            assertFalse(Thread.currentThread().isInterrupted());
            return new ProbeResult(ProbeResult.Status.CANCELLED);
        });
        var entries = service.read(new StringReader(codes(10)));
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = worker.submit(() -> service.check(entries, TIMEOUT, true, 3,
                    cancelled::get, update -> {}, message -> {}));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                cancelled.set(true);
                checkToken.countDown();
                assertTrue(observed.await(2, TimeUnit.SECONDS));
                assertFalse(result.isDone(), "Cancellation must not release the batch before all sockets are cleaned up");
                assertEquals(3, calls.get());
            } finally {
                cancelled.set(true);
                checkToken.countDown();
                cleanup.countDown();
            }
            var results = result.get(3, TimeUnit.SECONDS);
            assertTrue(results.stream().allMatch(row -> row.state() == BatchResult.State.CANCELLED));
            assertEquals(3, results.stream().filter(row -> row.probeResult().isPresent()).count());
            assertTrue(results.subList(3, 10).stream().allMatch(row -> row.detail().contains("Not tested")));
            assertEquals(3, calls.get());
        }
    }

    @Test
    void aCancelledProbeSignalsOtherActiveProbesAndStopsRemainingGroupsWithoutAnExternalToken() throws Exception {
        var entered = new CountDownLatch(2);
        var cancelFirst = new CountDownLatch(1);
        var firstPublished = new CountDownLatch(1);
        var checkSecond = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var service = service((request, token, progress) -> {
            calls.incrementAndGet();
            entered.countDown();
            if (request.did().value().equals("FFFF-1-A")) {
                await(cancelFirst);
            } else {
                await(checkSecond);
                assertTrue(token.getAsBoolean(), "The cancelled peer must signal the remaining probes");
            }
            return new ProbeResult(ProbeResult.Status.CANCELLED);
        });
        var entries = service.read(new StringReader(codes(6)));
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = worker.submit(() -> service.check(entries, TIMEOUT, false, 2, () -> false,
                    row -> { if (row.entry().lineNumber() == 1) { firstPublished.countDown(); } }, message -> {}));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                cancelFirst.countDown();
                assertTrue(firstPublished.await(2, TimeUnit.SECONDS));
                assertEquals(2, calls.get());
            } finally {
                cancelFirst.countDown();
                checkSecond.countDown();
            }
            assertTrue(result.get(3, TimeUnit.SECONDS).stream().allMatch(row -> row.state() == BatchResult.State.CANCELLED));
            assertEquals(2, calls.get());
        }
    }

    @Test
    void oneLocalFailureDoesNotAbortOtherDevicesAndInvalidRowsNeverUseAPoolSlot() throws Exception {
        var calls = new AtomicInteger();
        var service = service((request, token, progress) -> {
            calls.incrementAndGet();
            if (request.protocol() == TransportProtocol.XQ) {
                throw new IOException("Socket setup failed.");
            }
            return new ProbeResult(ProbeResult.Status.REACHABLE_RELAY);
        });
        var entries = service.read(new StringReader("FFFF-1-A\nbroken\nWWWW-2-B\nMISSING-3-C\nTTTT-4-D"));
        var results = service.check(entries, TIMEOUT, true, 3, () -> false, row -> {}, message -> {});
        assertEquals(List.of(BatchResult.State.FAILED, BatchResult.State.SKIPPED, BatchResult.State.CHECKED,
                BatchResult.State.SKIPPED, BatchResult.State.CHECKED), results.stream().map(BatchResult::state).toList());
        assertEquals("Socket setup failed.", results.getFirst().detail());
        assertEquals(3, calls.get());
        assertThrows(UnsupportedOperationException.class, results::clear);
    }

    @Test
    void cancellationBeforeSubmissionDoesNotOpenAnySessions() throws Exception {
        var service = service((request, token, progress) -> { throw new AssertionError("No probe may start"); });
        var results = service.check(service.read(new StringReader(codes(10))), TIMEOUT, true, 8,
                () -> true, row -> {}, message -> {});
        assertEquals(10, results.size());
        assertTrue(results.stream().allMatch(row -> row.state() == BatchResult.State.CANCELLED && row.probeResult().isEmpty()));
    }

    @Test
    void oneParallelSlotRetainsSequentialCompatibilityAndRejectsUnsafePoolSizes() throws Exception {
        var calls = new ArrayList<String>();
        var service = service((request, token, progress) -> {
            calls.add(request.did().value());
            return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
        });
        var entries = service.read(new StringReader(codes(3)));
        var results = service.check(entries, TIMEOUT, true, 1, () -> false, row -> {}, message -> {});
        assertEquals(entries.stream().map(row -> row.code()).toList(), calls);
        assertEquals(3, results.size());
        for (int parallelism : List.of(0, -1, 17)) {
            assertThrows(IllegalArgumentException.class, () -> service.check(entries, TIMEOUT, true, parallelism,
                    () -> false, row -> {}, message -> {}));
        }
        assertEquals(3, calls.size());
    }

    @Test
    void interruptingTheCoordinatorSignalsCancellationAndStillLetsActiveProbesCleanUpNormally() throws Exception {
        var entered = new CountDownLatch(2);
        var checkToken = new CountDownLatch(1);
        var cleanup = new CountDownLatch(1);
        var interruptedOnReturn = new AtomicBoolean();
        var coordinator = new AtomicReference<Thread>();
        var pendingCancelled = new CountDownLatch(1);
        var service = service((request, token, progress) -> {
            entered.countDown();
            await(checkToken);
            assertTrue(token.getAsBoolean());
            await(cleanup);
            assertFalse(Thread.currentThread().isInterrupted(), "Do not interrupt an owned socket's cleanup");
            return new ProbeResult(ProbeResult.Status.CANCELLED);
        });
        var entries = service.read(new StringReader(codes(4)));
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = worker.submit(() -> {
                coordinator.set(Thread.currentThread());
                var rows = service.check(entries, TIMEOUT, true, 2, () -> false, row -> {
                    if (row.probeResult().isEmpty()) { pendingCancelled.countDown(); }
                }, message -> {});
                interruptedOnReturn.set(Thread.currentThread().isInterrupted());
                return rows;
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                coordinator.get().interrupt();
                assertTrue(pendingCancelled.await(2, TimeUnit.SECONDS));
                checkToken.countDown();
                assertFalse(result.isDone());
            } finally {
                checkToken.countDown();
                cleanup.countDown();
            }
            assertEquals(4, result.get(3, TimeUnit.SECONDS).size());
            assertTrue(interruptedOnReturn.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"completion", "diagnostic", "probe"})
    void unexpectedFailuresStillSignalCancellationAndWaitForOtherActiveCleanup(String failureSource) throws Exception {
        var entered = new CountDownLatch(2);
        var failFirst = new CountDownLatch(1);
        var cleanupEntered = new CountDownLatch(1);
        var releaseCleanup = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var failure = new IllegalStateException("Failure in " + failureSource);
        var service = service((request, token, progress) -> {
            calls.incrementAndGet();
            entered.countDown();
            if (request.did().value().equals("FFFF-1-A")) {
                await(failFirst);
                if (failureSource.equals("probe")) { throw failure; }
                progress.accept("Fail diagnostic callback");
            } else {
                awaitCancellation(token);
                cleanupEntered.countDown();
                await(releaseCleanup);
                assertFalse(Thread.currentThread().isInterrupted());
            }
            return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
        });
        var entries = service.read(new StringReader(codes(5)));
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = worker.submit(() -> service.check(entries, TIMEOUT, false, 2, () -> false,
                    row -> { if (failureSource.equals("completion")) { throw failure; } },
                    message -> { if (failureSource.equals("diagnostic") && message.equals("[FFFF-1-A] Fail diagnostic callback")) {
                        throw failure;
                    } }));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                failFirst.countDown();
                assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS), "Active peers must receive cancellation after a failure");
                assertFalse(result.isDone(), "An unexpected failure must still wait for every socket's cleanup");
                assertEquals(2, calls.get());
            } finally {
                failFirst.countDown();
                releaseCleanup.countDown();
            }
            var thrown = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertSame(failure, thrown.getCause());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void emptyAndEntirelySkippedBatchesCompleteWithoutOpeningSessions() throws Exception {
        var service = service((request, token, progress) -> { throw new AssertionError("No probe may start"); });
        assertTrue(service.check(List.of(), TIMEOUT, true, 8, () -> false, row -> {}, message -> {}).isEmpty());
        var updates = new ArrayList<BatchResult>();
        var results = service.check(service.read(new StringReader("broken\nMISSING-1-A")), TIMEOUT, true, 8,
                () -> false, updates::add, message -> { throw new AssertionError("No network diagnostics expected"); });
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(row -> row.state() == BatchResult.State.SKIPPED));
        assertEquals(results, updates);
    }

    private static String codes(int count) {
        var lines = new StringBuilder();
        for (int serial = 1; serial <= count; serial++) {
            lines.append("FFFF-").append(serial).append("-A\n");
        }
        return lines.toString();
    }

    private static BatchAvailabilityService service(ProbeRunner runner) {
        return new BatchAvailabilityService(runner, new TestServerConfigurationProvider());
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IOException("Test did not release the probe");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Unexpected probe interruption", interrupted);
        }
    }

    private static void awaitCancellation(BooleanSupplier token) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        try {
            while (!token.getAsBoolean() && System.nanoTime() < deadline) { Thread.sleep(5); }
            assertTrue(token.getAsBoolean(), "The coordinator did not signal cancellation");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Unexpected probe interruption", interrupted);
        }
    }
}
