package com.ivanocj.cameras.availability.application.service;

import com.ivanocj.cameras.availability.application.model.BatchEntry;
import com.ivanocj.cameras.availability.application.model.BatchResult;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.testsupport.TestServerConfigurationProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BatchCsvImportTest {
    private final BatchAvailabilityService service = new BatchAvailabilityService((request, cancelled, progress) -> {
        throw new AssertionError("Import must not test devices");
    }, new TestServerConfigurationProvider());

    @Test
    void importsSerialsAndDescriptionsWithoutTreatingTheHeaderAsADevice() throws Exception {
        var entries = service.readCsv(new StringReader("Serial,Description\r\n"
                + " ffff-000009-demo ,Camera frontal\r\nWWWW-000002-B,Câmera entrada / Sala\r\n"));
        assertEquals(2, entries.size());
        assertEquals(List.of(2, 3), entries.stream().map(BatchEntry::lineNumber).toList());
        assertEquals("FFFF-000009-DEMO", entries.getFirst().code());
        assertEquals("Camera frontal", entries.getFirst().description());
        assertEquals("Câmera entrada / Sala", entries.get(1).description());
        assertEquals(TransportProtocol.XQ, entries.getFirst().profile().orElseThrow().protocol());
        assertTrue(entries.stream().allMatch(BatchEntry::canCheck));
    }

    @Test
    void supportsBomCaseInsensitiveReorderedHeadersAndEmptyDescriptions() throws Exception {
        var entries = service.readCsv(new StringReader(
                "\uFEFF description , SERIAL \nFront door,FFFF-1-A\n,WWWW-2-B"));
        assertEquals("FFFF-1-A", entries.getFirst().code());
        assertEquals("Front door", entries.getFirst().description());
        assertEquals("", entries.get(1).description());
        assertTrue(entries.get(1).canCheck());
    }

    @Test
    void supportsCsvQuotingCommasEscapedQuotesAndMultilineDescriptions() throws Exception {
        var entries = service.readCsv(new StringReader("Serial,Description\r\n"
                + "FFFF-1-A,\"Room, \"\"north\"\"\r\nsecond line\"\r\n"
                + "WWWW-2-B,\"  Preserve quoted spaces  \"\r\n"));
        assertEquals(List.of(2, 4), entries.stream().map(BatchEntry::lineNumber).toList());
        assertEquals("Room, \"north\"\r\nsecond line", entries.getFirst().description());
        assertEquals("  Preserve quoted spaces  ", entries.get(1).description());
    }

    @Test
    void retainsSourceLinesAcrossBlankRecords() throws Exception {
        var entries = service.readCsv(new StringReader("\nSerial,Description\n\nFFFF-1-A,Front\n\nWWWW-2-B,Back"));
        assertEquals(List.of(4, 6), entries.stream().map(BatchEntry::lineNumber).toList());
    }

    @Test
    void rejectsMissingOrDuplicateCsvColumnHeaders() {
        for (var header : List.of("Code,Name", "Serial,Serial", "Serial", "Serial,Description,Extra")) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> service.readCsv(new StringReader(header + "\nFFFF-1-A,Front")));
            assertTrue(failure.getMessage().contains("Serial and Description"));
        }
    }

    @Test
    void skipsRowsWithTheWrongColumnCountWithoutDroppingOtherRows() throws Exception {
        var entries = service.readCsv(new StringReader("Serial,Description\nFFFF-1-A\n"
                + "WWWW-2-B,Front,unexpected\nTTTT-3-C,Back"));
        assertEquals(3, entries.size());
        assertFalse(entries.get(0).canCheck());
        assertFalse(entries.get(1).canCheck());
        assertTrue(entries.get(0).error().contains("2 columns"));
        assertEquals("Front", entries.get(1).description());
        assertTrue(entries.get(2).canCheck());
    }

    @Test
    void preservesDescriptionsOfInvalidAndUnknownSerials() throws Exception {
        var entries = service.readCsv(new StringReader("Serial,Description\n,Missing serial\n"
                + "NNNN-000014-DEMO,Unknown deployment\nbroken,Invalid format"));
        assertTrue(entries.stream().noneMatch(BatchEntry::canCheck));
        assertEquals(List.of("Missing serial", "Unknown deployment", "Invalid format"),
                entries.stream().map(BatchEntry::description).toList());
        assertTrue(entries.get(1).error().contains("prefix NNNN"));
    }

    @Test
    void rejectsMalformedQuotingBeforeAnyChecksStart() {
        assertThrows(IOException.class, () -> service.readCsv(new StringReader(
                "Serial,Description\nFFFF-1-A,Front\nWWWW-2-B,\"Unclosed description")));
    }

    @Test
    void explainsAHeaderOnlyCsv() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> service.readCsv(new StringReader("Serial,Description\n")));
        assertTrue(failure.getMessage().contains("no device codes"));
    }

    @Test
    void doesNotCloseTheCallerOwnedReader() throws Exception {
        var closed = new AtomicBoolean();
        var reader = new StringReader("Serial,Description\nFFFF-1-A,Front") {
            @Override
            public void close() {
                closed.set(true);
                super.close();
            }
        };
        service.readCsv(reader);
        assertFalse(closed.get());
    }

    @Test
    void csvImportCanBeCancelledBeforeParsing() {
        var failure = assertThrows(CancellationException.class, () -> service.readCsv(
                new StringReader("Serial,Description\nFFFF-1-A,Front"), () -> true));
        assertTrue(failure.getMessage().contains("No codes were tested"));
    }

    @Test
    void descriptionsFollowRepeatedSerialsThroughIndependentResults() throws Exception {
        var runner = new BatchAvailabilityService((request, cancelled, progress) ->
                new ProbeResult(ProbeResult.Status.INCONCLUSIVE), new TestServerConfigurationProvider());
        var results = runner.check(runner.readCsv(new StringReader("Serial,Description\n"
                        + "FFFF-1-A,First\nFFFF-1-A,Second")), Duration.ofSeconds(1), false,
                () -> false, result -> {}, message -> {});
        assertEquals(List.of("First", "Second"), results.stream().map(result -> result.entry().description()).toList());
        assertTrue(results.stream().allMatch(result -> result.state() == BatchResult.State.CHECKED));
    }
}
