package com.ivanocj.cameras.availability.application.service;

import com.ivanocj.cameras.availability.application.logging.LogContext;
import com.ivanocj.cameras.availability.application.model.BatchEntry;
import com.ivanocj.cameras.availability.application.model.BatchResult;
import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.model.ServerProfile;
import com.ivanocj.cameras.availability.application.port.ProbeRunner;
import com.ivanocj.cameras.availability.application.port.ServerConfigurationProvider;
import com.ivanocj.cameras.availability.domain.model.DeviceId;
import com.ivanocj.cameras.availability.domain.model.DeviceIdentifier;
import com.ivanocj.cameras.availability.domain.model.GwellDeviceId;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;

import java.io.BufferedReader;
import java.io.FilterReader;
import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.apache.commons.csv.CSVFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Prefix/family-routed batch checks with bounded concurrency and serialized equivalent identities, including cleanup. */
public final class BatchAvailabilityService {
    private static final Logger LOG = LoggerFactory.getLogger(BatchAvailabilityService.class);
    public static final int DEFAULT_PARALLELISM = 8;
    public static final int MAX_PARALLELISM = 16;
    private final ProbeRunner runner;
    private final List<ServerProfile> profiles;

    public BatchAvailabilityService(ProbeRunner runner, ServerConfigurationProvider configurations) {
        this.runner = runner;
        this.profiles = List.copyOf(configurations.profiles());
    }

    /** Reads one DID per line. Blank lines and whole-line # comments are ignored; the caller owns the reader. */
    public List<BatchEntry> read(Reader source) throws IOException {
        return read(source, () -> false);
    }

    public List<BatchEntry> read(Reader source, BooleanSupplier cancelled) throws IOException {
        var reader = source instanceof BufferedReader buffered ? buffered : new BufferedReader(source);
        var entries = new ArrayList<BatchEntry>();
        int lineNumber = 0;
        while (true) {
            checkImportCancellation(cancelled);
            var line = reader.readLine();
            if (line == null) {
                break;
            }
            lineNumber++;
            if (lineNumber == 1 && line.startsWith("\uFEFF")) {
                line = line.substring(1);
            }
            var code = line.strip();
            if (!code.isEmpty() && !code.startsWith("#")) {
                entries.add(entry(lineNumber, code, ""));
            }
        }
        return requireEntries(entries);
    }

    /** Reads CSV records with Serial and Description headers. The caller retains ownership of the reader. */
    public List<BatchEntry> readCsv(Reader source) throws IOException {
        return readCsv(source, () -> false);
    }

    public List<BatchEntry> readCsv(Reader source, BooleanSupplier cancelled) throws IOException {
        var input = new PushbackReader(importReader(source, cancelled), 1);
        int first = input.read();
        if (first != -1 && first != '\uFEFF') {
            input.unread(first);
        }
        var format = CSVFormat.RFC4180.builder().setIgnoreEmptyLines(false).setIgnoreSurroundingSpaces(true).get();
        var entries = new ArrayList<BatchEntry>();
        int serialColumn = -1;
        int descriptionColumn = -1;
        int nextLine = 1;
        try (var parser = format.parse(input)) {
            for (var record : parser) {
                checkImportCancellation(cancelled);
                int lineNumber = nextLine;
                nextLine = Math.toIntExact(parser.getCurrentLineNumber() + 1);
                if (record.size() == 1 && record.get(0).isBlank()) {
                    continue;
                }
                if (serialColumn < 0) {
                    var headers = record.toList().stream().map(String::strip).toList();
                    if (headers.size() != 2 || !headers.stream().anyMatch("Serial"::equalsIgnoreCase)
                            || !headers.stream().anyMatch("Description"::equalsIgnoreCase)) {
                        throw new IllegalArgumentException("The CSV header must contain exactly Serial and Description columns.");
                    }
                    serialColumn = headers.getFirst().equalsIgnoreCase("Serial") ? 0 : 1;
                    descriptionColumn = 1 - serialColumn;
                    continue;
                }
                var serial = record.size() > serialColumn ? record.get(serialColumn).strip() : "";
                var description = record.size() > descriptionColumn ? record.get(descriptionColumn) : "";
                var entry = entry(lineNumber, serial, description);
                if (record.size() != 2) {
                    entry = new BatchEntry(lineNumber, entry.code(), description, entry.profile(),
                            "Expected 2 columns (Serial and Description). Quote descriptions containing commas.");
                }
                entries.add(entry);
            }
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
        if (serialColumn < 0) {
            throw new IllegalArgumentException("The CSV header must contain exactly Serial and Description columns.");
        }
        return requireEntries(entries);
    }

    private static List<BatchEntry> requireEntries(List<BatchEntry> entries) {
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("The file contains no device codes. Use Serial,Description CSV or one DID per text line.");
        }
        return List.copyOf(entries);
    }

    private static void checkImportCancellation(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Batch cancelled while reading the file. No codes were tested.");
        }
    }

    private static Reader importReader(Reader source, BooleanSupplier cancelled) {
        return new FilterReader(source) {
            @Override
            public int read() throws IOException {
                checkImportCancellation(cancelled);
                return super.read();
            }

            @Override
            public int read(char[] buffer, int offset, int length) throws IOException {
                checkImportCancellation(cancelled);
                return super.read(buffer, offset, length);
            }

            @Override
            public void close() {
                // The parser owns this wrapper, not the caller's underlying reader.
            }
        };
    }

    /** Sequential compatibility entry point. Each code keeps its full timeout and server/protocol selection. */
    public List<BatchResult> check(List<BatchEntry> entries, Duration timeout, boolean lanSearch,
                                   BooleanSupplier cancelled, Consumer<BatchResult> completed,
                                   Consumer<String> progress) {
        return check(entries, timeout, lanSearch, 1, cancelled, completed, progress);
    }

    private List<BatchResult> checkSequential(List<BatchEntry> entries, Duration timeout, boolean lanSearch,
                                              BooleanSupplier cancelled, Consumer<BatchResult> completed,
                                              Consumer<String> progress) {
        var results = new ArrayList<BatchResult>();
        boolean stopped = false;
        for (var entry : entries) {
            BatchResult result;
            if (stopped || cancelled.getAsBoolean()) {
                result = new BatchResult(entry, BatchResult.State.CANCELLED, Optional.empty(),
                        "Not tested because the batch was cancelled.");
            } else if (!entry.canCheck()) {
                result = new BatchResult(entry, BatchResult.State.SKIPPED, Optional.empty(), entry.error());
            } else {
                result = checkEntry(entry, timeout, lanSearch, cancelled, progress);
            }
            stopped |= result.state() == BatchResult.State.CANCELLED;
            results.add(result);
            completed.accept(result);
        }
        return List.copyOf(results);
    }

    /**
     * Checks distinct identities concurrently, never exceeding the requested limit. Repeated identities and
     * protocol-specific numeric aliases are serialized, without changing the original imported serial strings.
     * Callbacks run serially on the calling coordinator thread; updates arrive in completion order while
     * the returned immutable list retains source order. Cancellation waits for every active probe's cleanup.
     * The injected runner and cancellation supplier must support concurrent invocation when the limit exceeds one.
     */
    public List<BatchResult> check(List<BatchEntry> entries, Duration timeout, boolean lanSearch, int parallelism,
                                   BooleanSupplier cancelled, Consumer<BatchResult> completed, Consumer<String> progress) {
        if (parallelism < 1 || parallelism > MAX_PARALLELISM) {
            throw new IllegalArgumentException("Parallel batch checks must be between 1 and " + MAX_PARALLELISM + ".");
        }
        try (var operation = LogContext.batch()) {
            long started = System.nanoTime();
            LOG.info("event=batch_started rows={} parallelism={} timeout_ms={} lan_search={}",
                    entries.size(), parallelism, timeout.toMillis(), lanSearch);
            try {
                var results = parallelism == 1 ? checkSequential(entries, timeout, lanSearch, cancelled, completed, progress)
                        : new ParallelBatch(entries, timeout, lanSearch, parallelism, cancelled, completed, progress).run();
                LOG.info("event=batch_completed rows={} checked={} skipped={} failed={} cancelled={} elapsed_ms={}",
                        results.size(), count(results, BatchResult.State.CHECKED), count(results, BatchResult.State.SKIPPED),
                        count(results, BatchResult.State.FAILED), count(results, BatchResult.State.CANCELLED),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                return results;
            } catch (RuntimeException | Error failed) {
                LOG.error("event=batch_failed rows={} parallelism={}", entries.size(), parallelism, failed);
                throw failed;
            }
        }
    }

    private static long count(List<BatchResult> results, BatchResult.State state) {
        return results.stream().filter(result -> result.state() == state).count();
    }

    /** Only the coordinator mutates groups/results; worker threads own independent probes and emit diagnostics. */
    private final class ParallelBatch {
        private final List<BatchEntry> entries;
        private final Duration timeout;
        private final boolean lanSearch;
        private final int parallelism;
        private final BooleanSupplier cancelled;
        private final Consumer<BatchResult> completed;
        private final Consumer<String> progress;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final ConcurrentLinkedQueue<String> diagnostics = new ConcurrentLinkedQueue<>();
        private final Map<String, ArrayDeque<IndexedEntry>> groups = new LinkedHashMap<>();
        private final BatchResult[] results;
        private final Map<String, String> loggingContext = LogContext.snapshot();
        private boolean interrupted;

        ParallelBatch(List<BatchEntry> entries, Duration timeout, boolean lanSearch, int parallelism,
                      BooleanSupplier cancelled, Consumer<BatchResult> completed, Consumer<String> progress) {
            this.entries = List.copyOf(entries);
            this.timeout = timeout;
            this.lanSearch = lanSearch;
            this.parallelism = parallelism;
            this.cancelled = cancelled;
            this.completed = completed;
            this.progress = progress;
            results = new BatchResult[this.entries.size()];
        }

        List<BatchResult> run() {
            var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("batch-probe-", 0).factory());
            var completions = new ExecutorCompletionService<IndexedResult>(executor);
            var active = new HashMap<Future<IndexedResult>, ArrayDeque<IndexedEntry>>();
            try {
                if (Thread.interrupted()) {
                    interrupted = true;
                    stopped.set(true);
                }
                prepareGroups();
                var ready = new ArrayDeque<>(groups.values());
                boolean pendingCancelled = false;
                while (!ready.isEmpty() || !active.isEmpty()) {
                    flushDiagnostics();
                    if (shouldStop() && !pendingCancelled) {
                        for (var group : groups.values()) {
                            while (!group.isEmpty()) {
                                var job = group.removeFirst();
                                publish(new IndexedResult(job.index(), cancelledEntry(job.entry())));
                            }
                        }
                        ready.clear();
                        pendingCancelled = true;
                    }
                    while (active.size() < parallelism && !ready.isEmpty() && !shouldStop()) {
                        var group = ready.removeFirst();
                        var job = group.removeFirst();
                        var future = completions.submit(() -> {
                            try (var inherited = LogContext.inherit(loggingContext)) {
                                var result = shouldStop() ? cancelledEntry(job.entry())
                                        : checkEntry(job.entry(), timeout, lanSearch, this::shouldStop, diagnostics::add);
                                if (result.state() == BatchResult.State.CANCELLED) {
                                    stopped.set(true);
                                }
                                return new IndexedResult(job.index(), result);
                            }
                        });
                        active.put(future, group);
                    }
                    if (active.isEmpty()) {
                        continue;
                    }
                    try {
                        var future = completions.poll(100, TimeUnit.MILLISECONDS);
                        if (future != null) {
                            var result = future.get(); // Completed only after the runner's socket/session cleanup.
                            var group = active.remove(future);
                            if (!group.isEmpty()) {
                                ready.addLast(group);
                            }
                            flushDiagnostics();
                            publish(result);
                        }
                    } catch (InterruptedException interruption) {
                        interrupted = true;
                        stopped.set(true);
                    } catch (ExecutionException failed) {
                        var cause = failed.getCause();
                        if (cause instanceof RuntimeException runtime) {
                            throw runtime;
                        }
                        if (cause instanceof Error error) {
                            throw error;
                        }
                        throw new IllegalStateException("Unexpected batch probe failure.", cause);
                    }
                }
                flushDiagnostics();
                return List.copyOf(Arrays.asList(results));
            } finally {
                stopped.set(true);
                // Do not cancel/interrupt workers, even if a callback failed or the coordinator was interrupted.
                executor.shutdown();
                boolean terminated = false;
                while (!terminated) {
                    try {
                        terminated = executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException interruption) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void prepareGroups() {
            for (int index = 0; index < entries.size(); index++) {
                var entry = entries.get(index);
                if (shouldStop()) {
                    publish(new IndexedResult(index, cancelledEntry(entry)));
                } else if (!entry.canCheck()) {
                    publish(new IndexedResult(index, new BatchResult(entry, BatchResult.State.SKIPPED,
                            Optional.empty(), entry.error())));
                } else {
                    groups.computeIfAbsent(sessionKey(entry), unused -> new ArrayDeque<>())
                            .addLast(new IndexedEntry(index, entry));
                }
            }
        }

        private boolean shouldStop() {
            if (cancelled.getAsBoolean()) {
                stopped.set(true);
            }
            return stopped.get();
        }

        private void publish(IndexedResult result) {
            results[result.index()] = result.result();
            completed.accept(result.result());
        }

        private void flushDiagnostics() {
            String message;
            while ((message = diagnostics.poll()) != null) {
                progress.accept(message);
            }
        }
    }

    private record IndexedEntry(int index, BatchEntry entry) {}
    private record IndexedResult(int index, BatchResult result) {}

    private static String sessionKey(BatchEntry entry) {
        return entry.profile().orElseThrow().protocol().sessionKey(DeviceIdentifier.parseBatch(entry.code()));
    }

    private static BatchResult cancelledEntry(BatchEntry entry) {
        return new BatchResult(entry, BatchResult.State.CANCELLED, Optional.empty(),
                "Not tested because the batch was cancelled.");
    }

    private BatchEntry entry(int lineNumber, String code, String description) {
        DeviceIdentifier identifier;
        try {
            identifier = DeviceIdentifier.parseBatch(code);
        } catch (IllegalArgumentException invalid) {
            return new BatchEntry(lineNumber, code, description, Optional.empty(), invalid.getMessage());
        }
        var matches = identifier instanceof DeviceId did
                ? profiles.stream().filter(profile -> profile.protocol().legacyUdp() && profile.prefixes().contains(did.prefix())).toList()
                : profiles.stream().filter(profile -> profile.protocol()
                        == (identifier instanceof GwellDeviceId ? TransportProtocol.GWELL : TransportProtocol.IOTVIDEO)).toList();
        if (!(identifier instanceof DeviceId) && matches.stream().anyMatch(profile -> !profile.servers().isEmpty())) {
            // A LAN-only alternative must not make the single configured remote family route ambiguous.
            matches = matches.stream().filter(profile -> !profile.servers().isEmpty()).toList();
        }
        var route = identifier instanceof DeviceId did ? "prefix " + did.prefix()
                : identifier instanceof GwellDeviceId ? "GWell family" : "IoTVideo family";
        if (matches.isEmpty()) {
            return new BatchEntry(lineNumber, identifier.batchValue(), description, Optional.empty(),
                    "No known server profile for " + route + "; not tested.");
        }
        if (matches.size() > 1) {
            return new BatchEntry(lineNumber, identifier.batchValue(), description, Optional.empty(),
                    "Multiple server profiles match " + route + "; not tested.");
        }
        var profile = matches.getFirst();
        try {
            profile.protocol().validate(identifier);
            return new BatchEntry(lineNumber, identifier.batchValue(), description, Optional.of(profile), "");
        } catch (IllegalArgumentException invalid) {
            return new BatchEntry(lineNumber, identifier.batchValue(), description, Optional.of(profile), invalid.getMessage());
        }
    }

    private BatchResult checkEntry(BatchEntry entry, Duration timeout, boolean lanSearch,
                                    BooleanSupplier cancelled, Consumer<String> progress) {
        try (var operation = LogContext.probe(entry.lineNumber())) {
            var profile = entry.profile().orElseThrow();
            LOG.debug("event=batch_row_started source_line={} protocol={}", entry.lineNumber(), profile.protocol());
            progress.accept("Line " + entry.lineNumber() + ": " + entry.code() + " → " + profile.name()
                    + " · " + profile.protocol() + " · " + profile.serverText());
            try {
                var request = new ProbeRequest(DeviceIdentifier.parseBatch(entry.code()), profile.servers(), timeout, lanSearch,
                        profile.protocol());
                var result = runner.check(request, cancelled, message -> progress.accept("[" + entry.code() + "] " + message));
                var state = result.status() == ProbeResult.Status.CANCELLED
                        ? BatchResult.State.CANCELLED : BatchResult.State.CHECKED;
                LOG.debug("event=batch_row_completed source_line={} state={} status={}", entry.lineNumber(), state, result.status());
                return new BatchResult(entry, state, Optional.of(result), result.detail());
            } catch (IOException | IllegalArgumentException failure) {
                LOG.warn("event=batch_row_failed source_line={} protocol={}", entry.lineNumber(), profile.protocol(), failure);
                var detail = failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
                return new BatchResult(entry, BatchResult.State.FAILED, Optional.empty(), detail);
            }
        }
    }
}
