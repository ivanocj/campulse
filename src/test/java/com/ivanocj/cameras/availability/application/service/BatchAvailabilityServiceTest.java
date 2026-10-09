package com.ivanocj.cameras.availability.application.service;

import com.ivanocj.cameras.availability.application.model.BatchResult;
import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.model.ServerProfile;
import com.ivanocj.cameras.availability.application.port.ProbeRunner;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.testsupport.TestServerConfigurationProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BatchAvailabilityServiceTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(7);
    private static final String CODES = "WWWW-1-A\nFFFF-2-B\nTTTT-3-C\n";

    @Test
    void readsOneDidPerLineAndPreservesSourceLineNumbers() throws Exception {
        var entries = service(unusedRunner()).read(new StringReader(
                "\uFEFF# Devices\r\n\r\n  ffff-158409-ebdac  \r\n\t# comment\r\nWWWW-1-A\r\n"));
        assertEquals(List.of(3, 5), entries.stream().map(entry -> entry.lineNumber()).toList());
        assertEquals(List.of("FFFF-158409-EBDAC", "WWWW-1-A"),
                entries.stream().map(entry -> entry.code()).toList());
        assertTrue(entries.stream().allMatch(entry -> entry.canCheck()));
        assertThrows(UnsupportedOperationException.class, entries::clear);
    }

    @Test
    void acceptsAUtf8BomBeforeTheFirstCode() throws Exception {
        var entry = service(unusedRunner()).read(new StringReader("\uFEFFffff-1-a")).getFirst();
        assertEquals("FFFF-1-A", entry.code());
        assertTrue(entry.canCheck());
    }

    @Test
    void reportsMalformedUnknownAndUnsupportedCodesWithoutLosingOtherEntries() throws Exception {
        var entries = service(unusedRunner()).read(new StringReader(
                "not-a-did\nMISSING-1-A\nFFFF-4294967295-AAAAAA\nWWWW-1-A"));
        assertEquals(4, entries.size());
        assertTrue(entries.get(0).error().contains("PREFIX-SERIAL-CODE"));
        assertTrue(entries.get(1).error().contains("No known server profile for prefix MISSING"));
        assertTrue(entries.get(2).error().contains("20"));
        assertEquals(TransportProtocol.XQ, entries.get(2).profile().orElseThrow().protocol());
        assertFalse(entries.get(2).canCheck());
        assertTrue(entries.get(3).canCheck());
    }

    @Test
    void requiresAnExactPrefixMatchRatherThanPartOfAnotherPrefix() throws Exception {
        var entries = service(unusedRunner()).read(new StringReader("W-1-A\nFFFFX-1-A"));
        assertTrue(entries.stream().noneMatch(entry -> entry.canCheck()));
        assertTrue(entries.stream().allMatch(entry -> entry.error().contains("No known server profile")));
    }

    @Test
    void refusesAmbiguousPrefixMappingsInsteadOfChoosingAnArbitraryServer() throws Exception {
        var known = TestServerConfigurationProvider.profileNamed("HIP2P · XQ FFFF/HHHH/GGGG");
        var conflicting = new ServerProfile("Another deployment", known.servers(), TransportProtocol.PPCS,
                List.of("FFFF"));
        var service = new BatchAvailabilityService(unusedRunner(),
                new TestServerConfigurationProvider(List.of(known, conflicting)));
        var entry = service.read(new StringReader("FFFF-1-A")).getFirst();
        assertFalse(entry.canCheck());
        assertTrue(entry.error().contains("Multiple server profiles"));
    }

    @Test
    void aMissingCatalogDoesNotUseTheReferenceServersAsAFallback() throws Exception {
        var service = new BatchAvailabilityService(unusedRunner(), new TestServerConfigurationProvider(List.of()));
        assertFalse(service.read(new StringReader("FFFF-1-A")).getFirst().canCheck());
    }

    @Test
    void selectsServersAndTransportPerCodeAndUsesTheUsersSettings() throws Exception {
        var requests = new ArrayList<ProbeRequest>();
        var updates = new ArrayList<BatchResult>();
        var diagnostics = new ArrayList<String>();
        var service = service((request, cancelled, progress) -> {
            requests.add(request);
            progress.accept("Server hello acknowledged.");
            return new ProbeResult(ProbeResult.Status.REACHABLE_P2P);
        });
        var results = service.check(service.read(new StringReader(CODES)), TIMEOUT, false,
                () -> false, updates::add, diagnostics::add);
        assertEquals(List.of("WWWW-1-A", "FFFF-2-B", "TTTT-3-C"),
                requests.stream().map(request -> request.did().value()).toList());
        assertEquals(List.of(TransportProtocol.PPCS, TransportProtocol.XQ, TransportProtocol.PPCS),
                requests.stream().map(ProbeRequest::protocol).toList());
        assertEquals(TestServerConfigurationProvider.profileNamed("HIP2P · SY WWWW/TTTT/UUUU").servers(),
                requests.get(0).servers());
        assertEquals(TestServerConfigurationProvider.profileNamed("HIP2P · XQ FFFF/HHHH/GGGG").servers(),
                requests.get(1).servers());
        assertTrue(requests.stream().allMatch(request -> TIMEOUT.equals(request.timeout()) && !request.lanSearch()));
        assertEquals(results, updates);
        assertTrue(results.stream().allMatch(result -> result.state() == BatchResult.State.CHECKED));
        assertTrue(diagnostics.contains("[FFFF-2-B] Server hello acknowledged."));
        assertTrue(diagnostics.stream().anyMatch(line -> line.contains("HIP2P · XQ FFFF/HHHH/GGGG")));
    }

    @Test
    void skipsInvalidEntriesWithoutCallingTheProbe() throws Exception {
        var service = service(unusedRunner());
        var results = service.check(service.read(new StringReader("broken\nMISSING-1-A\nFFFF-4294967295-AAAAAA")),
                TIMEOUT, true, () -> false, result -> {}, message -> {});
        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(result -> result.state() == BatchResult.State.SKIPPED));
        assertTrue(results.stream().allMatch(result -> result.probeResult().isEmpty()));
    }

    @Test
    void aLocalFailureIsRecordedPerCodeAndDoesNotAbortTheBatch() throws Exception {
        var service = service((request, cancelled, progress) -> {
            if (request.protocol() == TransportProtocol.PPCS) {
                throw new IOException("Could not resolve this deployment.");
            }
            return new ProbeResult(ProbeResult.Status.REACHABLE_RELAY);
        });
        var results = service.check(service.read(new StringReader(CODES)), TIMEOUT, true,
                () -> false, result -> {}, message -> {});
        assertEquals(List.of(BatchResult.State.FAILED, BatchResult.State.CHECKED, BatchResult.State.FAILED),
                results.stream().map(BatchResult::state).toList());
        assertEquals("Could not resolve this deployment.", results.getFirst().detail());
        assertEquals(ProbeResult.Status.REACHABLE_RELAY, results.get(1).probeResult().orElseThrow().status());
    }

    @Test
    void cancellationLetsTheCurrentProbeReturnBeforeSkippingTheRemainingCodes() throws Exception {
        var cancelled = new AtomicBoolean();
        var requests = new ArrayList<ProbeRequest>();
        var cleanedUp = new AtomicBoolean();
        var service = service((request, token, progress) -> {
            requests.add(request);
            cancelled.set(true);
            assertTrue(token.getAsBoolean());
            cleanedUp.set(true);
            return new ProbeResult(ProbeResult.Status.CANCELLED);
        });
        var results = service.check(service.read(new StringReader(CODES)), TIMEOUT, true, cancelled::get,
                result -> assertTrue(cleanedUp.get()), message -> {});
        assertEquals(1, requests.size());
        assertTrue(results.stream().allMatch(result -> result.state() == BatchResult.State.CANCELLED));
        assertTrue(results.getFirst().probeResult().isPresent());
        assertTrue(results.get(1).probeResult().isEmpty());
        assertTrue(results.get(1).detail().contains("Not tested"));
    }

    @Test
    void cancellationBeforeTheBatchDoesNotStartAnyProbe() throws Exception {
        var service = service(unusedRunner());
        var results = service.check(service.read(new StringReader(CODES)), TIMEOUT, true,
                () -> true, result -> {}, message -> {});
        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(result -> result.state() == BatchResult.State.CANCELLED));
    }

    @Test
    void aCancelledProbeAlsoStopsTheBatchWithoutAnExternalToken() throws Exception {
        var requests = new ArrayList<ProbeRequest>();
        var service = service((request, cancelled, progress) -> {
            requests.add(request);
            return new ProbeResult(ProbeResult.Status.CANCELLED);
        });
        service.check(service.read(new StringReader(CODES)), TIMEOUT, true,
                () -> false, result -> {}, message -> {});
        assertEquals(1, requests.size());
    }

    @Test
    void repeatedCodesRetainTheirOwnResultAndSourceLine() throws Exception {
        var service = service((request, cancelled, progress) -> new ProbeResult(ProbeResult.Status.INCONCLUSIVE));
        var results = service.check(service.read(new StringReader("FFFF-1-A\nFFFF-1-A")), TIMEOUT, true,
                () -> false, result -> {}, message -> {});
        assertEquals(List.of(1, 2), results.stream().map(result -> result.entry().lineNumber()).toList());
    }

    @Test
    void explainsAnEmptyOrCommentOnlyFile() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> service(unusedRunner()).read(new StringReader("\n# no codes\n")));
        assertTrue(failure.getMessage().contains("no device codes"));
    }

    @Test
    void canCancelWhileImportingWithoutStartingAnyNetworkWork() {
        var polls = new AtomicInteger();
        var failure = assertThrows(CancellationException.class, () -> service(unusedRunner())
                .read(new StringReader(CODES), () -> polls.incrementAndGet() > 1));
        assertTrue(failure.getMessage().contains("No codes were tested"));
    }

    private static BatchAvailabilityService service(ProbeRunner runner) {
        return new BatchAvailabilityService(runner, new TestServerConfigurationProvider());
    }

    private static ProbeRunner unusedRunner() {
        return (request, cancelled, progress) -> {
            throw new AssertionError("No network work should be started");
        };
    }
}
