package com.ivanocj.cameras.availability.bootstrap;

import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.presentation.swing.AvailabilityPanel;
import com.ivanocj.cameras.availability.presentation.swing.AvailabilityPreviewFactory;
import com.ivanocj.cameras.availability.presentation.swing.AvailabilityTheme;
import com.ivanocj.cameras.availability.presentation.swing.FormMemory;
import com.ivanocj.cameras.availability.presentation.swing.FormMemory.FormState;
import com.ivanocj.cameras.availability.presentation.swing.PreferencesFormMemory;
import com.ivanocj.cameras.availability.testsupport.InMemoryPreferencesFactory;
import com.ivanocj.cameras.availability.testsupport.PreferencesTestStore;
import static com.ivanocj.cameras.availability.presentation.swing.AvailabilityPreviewFactory.component;

import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Opt-in confined timing experiment. In-memory baseline and SIMULATED stalls, never native Preferences. */
public final class PreferencesLatencyPreview {
    private static final FormState SEED = new FormState("FFFF-1-A", "Bell reference", "127.0.0.1:32100",
            TransportProtocol.XQ, 15, false);

    private PreferencesLatencyPreview() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 2) { throw new IllegalArgumentException("Use [repetitions] [simulated delay ms]."); }
        int repetitions = args.length >= 1 ? Integer.parseInt(args[0]) : 3;
        int delayMillis = args.length == 2 ? Integer.parseInt(args[1]) : 250;
        require(repetitions > 0 && delayMillis >= 0, "Invalid measurement parameters");
        require(InMemoryPreferencesFactory.class.getName().equals(System.getProperty("java.util.prefs.PreferencesFactory")),
                "The test-only in-memory Preferences factory must be selected at JVM startup");
        var root = Preferences.userRoot();
        require(root.getClass().getEnclosingClass() == InMemoryPreferencesFactory.class, "Refusing a platform Preferences store");
        var unexpected = new AtomicReference<Throwable>();
        var previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            unexpected.compareAndSet(null, failure);
            failure.printStackTrace(System.err);
        });
        try {
            SwingUtilities.invokeAndWait(() -> {
                AvailabilityTheme.install();
                // Warm layout/fonts/native window code without reading settings or starting a check.
                var panel = AvailabilityPreviewFactory.panel();
                if (!GraphicsEnvironment.isHeadless()) {
                    var window = new JFrame();
                    AvailabilityApplication.showWindow(window, panel);
                    window.dispose();
                }
                panel.close();
            });
            System.out.println("R8: Java " + System.getProperty("java.version") + ", headless=" + GraphicsEnvironment.isHeadless()
                    + ", simulated_delay_ms=" + delayMillis + ". NO native Preferences latency is measured.");
            System.out.println("Queued pulse waits for the enclosing EDT action; panel-load includes remaining view/window setup.");
            System.out.println("store,operation,trial,call_ms,queued_pulse_ms");
            for (var mode : List.of("in-memory", "SIMULATED-read", "SIMULATED-write", "SIMULATED-flush", "SIMULATED-failed-flush")) {
                var armed = new AtomicBoolean();
                Preferences node = mode.equals("in-memory") ? root.node("r8-preview-" + UUID.randomUUID())
                        : new PreferencesTestStore(key -> {
                            if (armed.get() && mode.equals("SIMULATED-read") && key.equals("did")) { delay(delayMillis); }
                        }, key -> {
                            if (armed.get() && mode.equals("SIMULATED-write") && key.equals("did")) { delay(delayMillis); }
                        }, () -> {
                            if (armed.get() && mode.contains("flush")) {
                                delay(delayMillis);
                                if (mode.equals("SIMULATED-failed-flush")) { throw new BackingStoreException("Synthetic flush failure"); }
                            }
                        });
                try {
                    var memory = new PreferencesFormMemory(node);
                    memory.save(SEED);
                    for (int warm = 0; warm < 50; warm++) { memory.load(); memory.save(SEED); node.flush(); }
                    armed.set(true);
                    for (int trial = 1; trial <= repetitions; trial++) {
                        var loaded = new AtomicReference<Optional<FormState>>();
                        var loadTime = measure(() -> loaded.set(memory.load()));
                        require(Optional.of(SEED).equals(loaded.get()), "Incorrect restoration");
                        print(mode, "load", trial, loadTime);
                        print(mode, "save", trial, measure(() -> memory.save(SEED)));
                        print(mode, "flush", trial, measure(() -> {
                            try { node.flush(); }
                            catch (BackingStoreException failed) { require(mode.equals("SIMULATED-failed-flush"), "Unexpected flush failure"); }
                        }));
                    }
                    panelMeasurements(mode, memory, repetitions);
                } finally {
                    if (node.parent() != null) { node.removeNode(); } // Only the verified in-memory factory has children.
                }
            }
            if (unexpected.get() != null) { throw new IllegalStateException("Asynchronous measurement failure", unexpected.get()); }
        } finally { Thread.setDefaultUncaughtExceptionHandler(previousHandler); }
    }

    private static void panelMeasurements(String mode, FormMemory memory, int repetitions) throws Exception {
        var measured = new MeasuredMemory(memory);
        var panel = new AtomicReference<AvailabilityPanel>();
        var frame = new AtomicReference<JFrame>();
        var terminal = new AtomicReference<CountDownLatch>();
        var probeFailure = new AtomicReference<Throwable>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var view = AvailabilityPreviewFactory.memoryPanel(measured, (request, cancelled, progress) -> {
                    try {
                        require(!SwingUtilities.isEventDispatchThread(), "Probe ran on the EDT");
                        require(measured.returned && measured.latest.did().equals(request.identifier().value()), "Probe started before its save returned");
                        require(measured.latest.protocol() == request.protocol(), "Protocol changed");
                        require(request.timeout().toSeconds() == SEED.timeoutSeconds() && request.lanSearch() == SEED.lanSearch(), "Settings changed");
                    } catch (Throwable failed) { probeFailure.compareAndSet(null, failed); }
                    return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
                });
                panel.set(view);
                component(view, "check", JButton.class).addPropertyChangeListener("enabled", event -> {
                    if (Boolean.TRUE.equals(event.getNewValue()) && terminal.get() != null) { terminal.get().countDown(); }
                });
                if (!GraphicsEnvironment.isHeadless()) {
                    var window = new JFrame();
                    frame.set(window);
                    AvailabilityApplication.showWindow(window, view);
                    window.setTitle("R8 — " + mode + "; no native Preferences, network or credentials");
                }
            });
            SwingUtilities.invokeAndWait(() -> { }); // Settle the load pulse after constructor/window work.
            print(mode, "panel-load", 1, measured.load);
            for (int trial = 1; trial <= repetitions; trial++) {
                var done = new CountDownLatch(1);
                terminal.set(done);
                String did = "FFFF-" + (100 + trial) + "-A";
                SwingUtilities.invokeAndWait(() -> {
                    component(panel.get(), "did", JTextField.class).setText(did);
                    component(panel.get(), "check", JButton.class).doClick(0);
                });
                require(done.await(10, TimeUnit.SECONDS), "Fake check did not finish");
                SwingUtilities.invokeAndWait(() -> { });
                if (probeFailure.get() != null) { throw new IllegalStateException("Persistence ordering changed", probeFailure.get()); }
                print(mode, "panel-save", trial, measured.save);
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (panel.get() != null) { panel.get().close(); }
                if (frame.get() != null) { frame.get().dispose(); }
            });
        }
    }

    private static Timing measure(Runnable operation) throws Exception {
        var result = new AtomicReference<Timing>();
        SwingUtilities.invokeAndWait(() -> {
            var timing = new Timing();
            operation.run();
            timing.end();
            result.set(timing);
        });
        SwingUtilities.invokeAndWait(() -> { });
        return result.get();
    }

    private static void print(String mode, String operation, int trial, Timing timing) {
        System.out.printf(Locale.ROOT, "%s,%s,%d,%.3f,%.3f%n", mode, operation, trial,
                timing.callNanos / 1_000_000.0, timing.pulseNanos.get() / 1_000_000.0);
    }

    private static void delay(int milliseconds) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds);
        while (System.nanoTime() < deadline) {
            require(!Thread.currentThread().isInterrupted(), "Fixture interrupted");
            LockSupport.parkNanos(Math.max(1, deadline - System.nanoTime()));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }

    private static final class Timing {
        private final long started;
        private final AtomicLong pulseNanos = new AtomicLong();
        private long callNanos;

        Timing() {
            require(SwingUtilities.isEventDispatchThread(), "Timing calls must use the EDT");
            long posted = System.nanoTime();
            SwingUtilities.invokeLater(() -> pulseNanos.set(System.nanoTime() - posted));
            started = System.nanoTime();
        }

        void end() { callNanos = System.nanoTime() - started; }
    }

    private static final class MeasuredMemory implements FormMemory {
        private final FormMemory delegate;
        private Timing load;
        private Timing save;
        private volatile boolean returned;
        private volatile FormState latest;

        MeasuredMemory(FormMemory delegate) { this.delegate = delegate; }

        @Override public Optional<FormState> load() {
            load = new Timing();
            try { return delegate.load(); }
            finally { load.end(); }
        }

        @Override public void save(FormState state) {
            returned = false;
            save = new Timing();
            try { delegate.save(state); latest = state; }
            finally { save.end(); returned = true; }
        }
    }
}
