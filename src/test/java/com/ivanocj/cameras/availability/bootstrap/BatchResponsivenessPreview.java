package com.ivanocj.cameras.availability.bootstrap;

import com.ivanocj.cameras.availability.application.model.P2pInformation;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.presentation.swing.AvailabilityPanel;
import com.ivanocj.cameras.availability.presentation.swing.AvailabilityPreviewFactory;
import com.ivanocj.cameras.availability.presentation.swing.AvailabilityTheme;
import static com.ivanocj.cameras.availability.presentation.swing.AvailabilityPreviewFactory.component;

import java.awt.AWTEvent;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;

/** Opt-in latency measurement, not a timing-sensitive test. No network, credentials or platform Preferences. */
public final class BatchResponsivenessPreview {
    private BatchResponsivenessPreview() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 2) { throw new IllegalArgumentException("Use [row count] [repetitions]."); }
        int rows = args.length >= 1 ? Integer.parseInt(args[0]) : 5000;
        int repetitions = args.length == 2 ? Integer.parseInt(args[1]) : 3;
        if (rows < 1 || repetitions < 1) { throw new IllegalArgumentException("Counts must be positive."); }
        var queue = new MeasuredEventQueue();
        var unexpected = new AtomicReference<Throwable>();
        var previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            unexpected.compareAndSet(null, failure);
            failure.printStackTrace(System.err);
        });
        SwingUtilities.invokeAndWait(() -> {
            AvailabilityTheme.install();
            Toolkit.getDefaultToolkit().getSystemEventQueue().push(queue);
        });
        try {
            System.out.println("Synthetic EDT measurement: Java " + System.getProperty("java.version")
                    + ", headless=" + GraphicsEnvironment.isHeadless()
                    + ", parallelism=8, 4 diagnostic messages/probe, zero probe delay, diagnostics expanded.");
            System.out.println("mode,rows,trial,wall_ms,queue_samples,queue_p95_ms,queue_max_ms,dispatch_max_ms,table_events,progress_changes,log_insertions");
            for (boolean filtered : List.of(false, true)) {
                run(100, filtered, queue); // Warm class loading, layouts, sorter and document code.
                for (int trial = 1; trial <= repetitions; trial++) {
                    var result = run(rows, filtered, queue);
                    System.out.printf(Locale.ROOT, "%s,%d,%d,%.1f,%d,%.1f,%.1f,%.1f,%d,%d,%d%n",
                            filtered ? "outcome+elapsed-sort" : "all", rows, trial, result.wallMillis(),
                            result.queueDelays().size(), percentile(result.queueDelays(), 0.95),
                            percentile(result.queueDelays(), 1), result.maxDispatchMillis(),
                            result.tableEvents(), result.progressChanges(), result.logInsertions());
                }
            }
            if (unexpected.get() != null) { throw new IllegalStateException("Asynchronous preview failure", unexpected.get()); }
        } finally {
            SwingUtilities.invokeAndWait(queue::remove);
            Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        }
    }

    private static Measurement run(int rows, boolean filtered, MeasuredEventQueue queue) throws Exception {
        var root = Path.of("target/ux-rescue-build/preview");
        Files.createDirectories(root);
        var file = Files.createTempDirectory(root, "responsiveness-").resolve("synthetic.csv");
        var csv = new StringBuilder("Serial,Description\n");
        int testable = 0;
        int direct = 0;
        for (int row = 0; row < rows; row++) {
            String code = switch (row % 6) {
                case 0 -> "FFFF-" + (row + 1) + "-A";
                case 1 -> "WWWW-" + (row + 1) + "-A";
                case 2 -> "GWELL:0" + (row + 1);
                case 3 -> "IOTVIDEO:Ab_" + (row + 1);
                case 4 -> "FFFF-1-A"; // Repeated identity, independent description/source line.
                default -> "MISSING-" + (row + 1) + "-A";
            };
            if (row % 6 != 5) { testable++; }
            if (row % 6 == 0 || row % 6 == 4) { direct++; }
            csv.append(code).append(",\"Câmera ").append(row + 1).append(", [north]\"\n");
        }
        Files.writeString(file, csv);
        var calls = new AtomicInteger();
        var tableEvents = new AtomicInteger();
        var progressChanges = new AtomicInteger();
        var logInsertions = new AtomicInteger();
        var panel = new AtomicReference<AvailabilityPanel>();
        var frame = new AtomicReference<JFrame>();
        var done = new CountDownLatch(1);
        var completionFailure = new AtomicReference<Throwable>();
        var finished = new AtomicBoolean();
        var queueDelays = new ArrayList<Long>(); // Only the EDT writes/reads this list.
        var heartbeatPending = new AtomicBoolean();
        var sampler = Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "preview-edt-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        long started = System.nanoTime();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var view = AvailabilityPreviewFactory.batchPanel(file, (request, cancelled, progress) -> {
                    require(!SwingUtilities.isEventDispatchThread(), "Fake probe ran on the EDT");
                    int call = calls.incrementAndGet();
                    for (int stage = 1; stage <= 4; stage++) { progress.accept("Synthetic stage " + stage); }
                    var status = switch (request.protocol()) {
                        case XQ -> ProbeResult.Status.REACHABLE_P2P;
                        case PPCS -> ProbeResult.Status.REACHABLE_RELAY;
                        case GWELL -> ProbeResult.Status.AUTH_REQUIRED;
                        case IOTVIDEO -> ProbeResult.Status.REPORTED_ONLINE;
                    };
                    var mode = status == ProbeResult.Status.REACHABLE_P2P ? P2pInformation.ConnectionMode.DIRECT_P2P
                            : status == ProbeResult.Status.REACHABLE_RELAY ? P2pInformation.ConnectionMode.UDP_RELAY
                            : P2pInformation.ConnectionMode.NONE;
                    var peer = new InetSocketAddress(mode == P2pInformation.ConnectionMode.UDP_RELAY ? "192.0.2.8" : "198.51.100.8", 32108);
                    var information = new P2pInformation(Optional.of(request.protocol()), mode, Optional.empty(), List.of(), List.of(),
                            mode == P2pInformation.ConnectionMode.DIRECT_P2P ? List.of(peer) : List.of(),
                            mode == P2pInformation.ConnectionMode.UDP_RELAY ? List.of(peer) : List.of(), Map.of());
                    return new ProbeResult(status, "Synthetic evidence " + call, status != ProbeResult.Status.AUTH_REQUIRED,
                            mode == P2pInformation.ConnectionMode.NONE ? Optional.empty() : Optional.of(peer),
                            Optional.empty(), Duration.ofMillis(call % 100), information);
                });
                panel.set(view);
                component(view, "diagnosticsToggle", JToggleButton.class).doClick(0);
                var table = component(view, "batchResults", JTable.class);
                if (filtered) {
                    component(view, "batchOutcomeFilter", JComboBox.class).setSelectedItem("Reachable · direct P2P");
                    table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(11, SortOrder.ASCENDING)));
                }
                table.getModel().addTableModelListener(event -> {
                    require(SwingUtilities.isEventDispatchThread(), "Table changed off the EDT");
                    tableEvents.incrementAndGet();
                });
                component(view, "progress", JProgressBar.class).addChangeListener(event -> {
                    require(SwingUtilities.isEventDispatchThread(), "Progress changed off the EDT");
                    progressChanges.incrementAndGet();
                });
                component(view, "log", JTextArea.class).getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                    @Override public void insertUpdate(javax.swing.event.DocumentEvent event) {
                        require(SwingUtilities.isEventDispatchThread(), "Log changed off the EDT");
                        logInsertions.incrementAndGet();
                    }
                    @Override public void removeUpdate(javax.swing.event.DocumentEvent event) { }
                    @Override public void changedUpdate(javax.swing.event.DocumentEvent event) { }
                });
                component(view, "check", JButton.class).addPropertyChangeListener("enabled", event -> {
                    if (Boolean.TRUE.equals(event.getNewValue())) {
                        try {
                            require("Batch complete".equals(component(view, "status", JLabel.class).getText()), "Unexpected terminal status");
                            require(allRowsSettled(table, rows), "Controls unlocked before every result reached the table");
                        } catch (Throwable failed) { completionFailure.compareAndSet(null, failed); }
                        finally {
                            finished.set(true);
                            done.countDown();
                        }
                    }
                });
                if (!GraphicsEnvironment.isHeadless()) {
                    var window = new JFrame();
                    frame.set(window);
                    AvailabilityApplication.showWindow(window, view);
                    window.setTitle("R7 MEASUREMENT — synthetic batch, no network or private accounts");
                }
            });
            SwingUtilities.invokeAndWait(queue::begin);
            sampler.scheduleAtFixedRate(() -> {
                if (!finished.get() && heartbeatPending.compareAndSet(false, true)) {
                    long posted = System.nanoTime();
                    SwingUtilities.invokeLater(() -> {
                        queueDelays.add(System.nanoTime() - posted);
                        heartbeatPending.set(false);
                    });
                }
            }, 0, 10, TimeUnit.MILLISECONDS);
            started = System.nanoTime();
            SwingUtilities.invokeAndWait(() -> component(panel.get(), "batchCheck", JButton.class).doClick(0));
            require(done.await(120, TimeUnit.SECONDS), "Synthetic batch did not finish in 120 seconds");
            if (completionFailure.get() != null) { throw new IllegalStateException("Invalid completion order", completionFailure.get()); }
            long elapsed = System.nanoTime() - started;
            sampler.shutdownNow();
            require(sampler.awaitTermination(5, TimeUnit.SECONDS), "Heartbeat sampler did not stop");
            queue.end();
            var measurement = new AtomicReference<Measurement>();
            int expectedCalls = testable;
            int expectedVisible = filtered ? direct : rows;
            SwingUtilities.invokeAndWait(() -> {
                var view = panel.get();
                var table = component(view, "batchResults", JTable.class);
                require(calls.get() == expectedCalls, "Incorrect fake-probe count");
                require(table.getRowCount() == expectedVisible, "Active filter lost rows");
                require(table.getColumnCount() == 12, "Changed table columns");
                require(component(view, "batchSummary", JLabel.class).getText().contains(rows + " entries"), "Incomplete batch summary");
                require(component(view, "log", JTextArea.class).getLineCount() - 1 <= 2000, "Unbounded diagnostics");
                measurement.set(new Measurement(elapsed / 1_000_000.0, List.copyOf(queueDelays), queue.maxMillis(),
                        tableEvents.get(), progressChanges.get(), logInsertions.get()));
            });
            return measurement.get();
        } finally {
            finished.set(true);
            sampler.shutdownNow();
            queue.end();
            SwingUtilities.invokeAndWait(() -> {
                if (panel.get() != null) { panel.get().close(); }
                if (frame.get() != null) { frame.get().dispose(); }
            });
        }
    }

    private static boolean allRowsSettled(JTable table, int rows) {
        var model = table.getModel();
        if (model.getRowCount() != rows) { return false; }
        for (int row = 0; row < rows; row++) {
            if (!Integer.valueOf(row + 2).equals(model.getValueAt(row, 0)) || "Queued".equals(model.getValueAt(row, 5))) { return false; }
        }
        return true;
    }

    private static double percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) { return 0; }
        var sorted = values.stream().sorted().toList();
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1)) / 1_000_000.0;
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }

    private record Measurement(double wallMillis, List<Long> queueDelays, double maxDispatchMillis,
                               int tableEvents, int progressChanges, int logInsertions) { }

    private static final class MeasuredEventQueue extends EventQueue {
        private volatile boolean measuring;
        private long maxNanos;

        void begin() { maxNanos = 0; measuring = true; }
        void end() { measuring = false; }
        double maxMillis() { return maxNanos / 1_000_000.0; }
        void remove() { pop(); }

        @Override protected void dispatchEvent(AWTEvent event) {
            boolean record = measuring;
            long started = System.nanoTime();
            try { super.dispatchEvent(event); }
            finally {
                if (record) { maxNanos = Math.max(maxNanos, System.nanoTime() - started); }
            }
        }
    }
}
