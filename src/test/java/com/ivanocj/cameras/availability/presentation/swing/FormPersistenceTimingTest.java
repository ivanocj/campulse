package com.ivanocj.cameras.availability.presentation.swing;

import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.port.ProbeRunner;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.presentation.swing.FormMemory.FormState;
import com.ivanocj.cameras.availability.testsupport.PreferencesTestStore;
import com.ivanocj.cameras.availability.testsupport.TestServerConfigurationProvider;
import static com.ivanocj.cameras.availability.presentation.swing.AvailabilityPanelTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.BackingStoreException;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Characterizes the current synchronous contract before any R8 persistence-timing decision. */
class FormPersistenceTimingTest {
    private static final FormState SAVED = new FormState("FFFF-1-A", "Bell reference", "127.0.0.1:32100",
            TransportProtocol.XQ, 15, false);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aProbeStartsOnlyAfterTheEdtSaveAndFlushAttemptReturnEvenIfFlushFails(boolean flushFails) throws Exception {
        var armed = new AtomicBoolean();
        var flushReturned = new AtomicBoolean();
        var flushEntered = new CountDownLatch(1);
        var releaseFlush = new CountDownLatch(1);
        var releaseProbe = new CountDownLatch(1);
        var probeEntered = new CountDownLatch(1);
        var pulse = new CountDownLatch(1);
        var events = new CopyOnWriteArrayList<String>();
        var node = new PreferencesTestStore(key -> { }, key -> {
            if (armed.get()) {
                assertTrue(SwingUtilities.isEventDispatchThread());
                events.add("put:" + key);
            }
        }, () -> {
            if (!armed.get()) { return; }
            assertTrue(SwingUtilities.isEventDispatchThread());
            events.add("flush:begin");
            flushEntered.countDown();
            await(releaseFlush);
            events.add("flush:end");
            flushReturned.set(true);
            if (flushFails) { throw new BackingStoreException("Synthetic flush failure"); }
        });
        var memory = new PreferencesFormMemory(node);
        memory.save(SAVED);
        var view = new AtomicReference<AvailabilityPanel>();
        var done = new CountDownLatch(1);
        try {
            SwingUtilities.invokeAndWait(() -> {
                var panel = panel(memory, (request, cancelled, progress) -> {
                    assertFalse(SwingUtilities.isEventDispatchThread());
                    assertTrue(flushReturned.get(), "Save/flush must return before the probe starts");
                    assertEquals(SAVED.did(), request.identifier().value());
                    assertEquals(SAVED.protocol(), request.protocol());
                    assertEquals(SAVED.timeoutSeconds(), request.timeout().toSeconds());
                    assertEquals(SAVED.lanSearch(), request.lanSearch());
                    probeEntered.countDown();
                    await(releaseProbe);
                    return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
                });
                view.set(panel);
                onStatus(panel, "Inconclusive", done);
            });
            armed.set(true);
            var submit = new FutureTask<Void>(() -> field(view.get(), "check", JButton.class).doClick(0), null);
            SwingUtilities.invokeLater(submit);
            assertTrue(flushEntered.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeLater(pulse::countDown);
            assertFalse(submit.isDone());
            assertEquals(1, probeEntered.getCount());
            assertEquals(1, pulse.getCount(), "An EDT action cannot execute while synchronous flush is blocked");
            assertEquals(List.of("put:did", "put:profile", "put:servers", "put:protocol", "put:timeoutSeconds", "put:lanSearch", "flush:begin"), events);
            releaseFlush.countDown();
            submit.get(5, TimeUnit.SECONDS);
            assertTrue(probeEntered.await(5, TimeUnit.SECONDS));
            assertTrue(pulse.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(field(view.get(), "check", JButton.class).isEnabled());
                field(view.get(), "did", JTextField.class).setText("Unsubmitted edit");
                assertEquals(Optional.of(SAVED), memory.load(), "Do not persist edits made after the submitted snapshot");
            });
            releaseProbe.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals("flush:end", events.getLast());
        } finally {
            releaseFlush.countDown();
            releaseProbe.countDown();
            close(view);
        }
    }

    @Test
    void slowStartupLoadRunsOnTheEdtAndRestoresBeforeThePanelIsExposed() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pulse = new CountDownLatch(1);
        var node = new PreferencesTestStore(key -> {
            assertTrue(SwingUtilities.isEventDispatchThread());
            if (key.equals("did")) { entered.countDown(); await(release); }
        }, key -> { }, () -> { });
        new PreferencesFormMemory(node).save(SAVED);
        var creation = new FutureTask<>(() -> panel(new PreferencesFormMemory(node), (request, cancelled, progress) -> {
            throw new AssertionError("Loading settings must not start a probe");
        }));
        try {
            SwingUtilities.invokeLater(creation);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeLater(pulse::countDown);
            assertFalse(creation.isDone());
            assertEquals(1, pulse.getCount());
        } finally { release.countDown(); }
        var panel = creation.get(5, TimeUnit.SECONDS);
        assertTrue(pulse.await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(SAVED.did(), field(panel, "did", JTextField.class).getText());
            assertTrue(field(panel, "check", JButton.class).isEnabled());
            panel.close();
        });
    }

    @Test
    void successiveSubmissionsKeepTheLastSubmittedSnapshotAndInvalidInputDoesNotSave() throws Exception {
        var flushes = new AtomicInteger();
        var node = new PreferencesTestStore(key -> { }, key -> { }, () -> flushes.incrementAndGet());
        var memory = new PreferencesFormMemory(node);
        var view = new AtomicReference<AvailabilityPanel>();
        try {
            for (int serial = 1; serial <= 2; serial++) {
                var done = new CountDownLatch(1);
                String did = "FFFF-" + serial + "-A";
                SwingUtilities.invokeAndWait(() -> {
                    if (view.get() == null) { view.set(panel(memory, (request, cancelled, progress) -> new ProbeResult(ProbeResult.Status.INCONCLUSIVE))); }
                    var panel = view.get();
                    var status = field(panel, "status", JLabel.class);
                    var listener = new java.beans.PropertyChangeListener() {
                        @Override public void propertyChange(java.beans.PropertyChangeEvent event) {
                            if ("Inconclusive".equals(event.getNewValue())) { status.removePropertyChangeListener("text", this); done.countDown(); }
                        }
                    };
                    status.addPropertyChangeListener("text", listener);
                    field(panel, "did", JTextField.class).setText(did);
                    field(panel, "check", JButton.class).doClick(0);
                });
                assertTrue(done.await(5, TimeUnit.SECONDS));
                SwingUtilities.invokeAndWait(() -> assertEquals(did, memory.load().orElseThrow().did()));
            }
            SwingUtilities.invokeAndWait(() -> {
                var panel = view.get();
                field(panel, "did", JTextField.class).setText("invalid");
                field(panel, "check", JButton.class).doClick(0);
                assertEquals("FFFF-2-A", memory.load().orElseThrow().did());
                assertEquals(2, flushes.get());
            });
        } finally { close(view); }
    }

    @Test
    void aCloseQueuedDuringFlushWaitsForSaveThenSignalsTheOwnedProbeThroughCleanup() throws Exception {
        var flushEntered = new CountDownLatch(1);
        var releaseFlush = new CountDownLatch(1);
        var closeDelivered = new CountDownLatch(1);
        var cleanupEntered = new CountDownLatch(1);
        var releaseCleanup = new CountDownLatch(1);
        var node = new PreferencesTestStore(key -> { }, key -> { }, () -> { flushEntered.countDown(); await(releaseFlush); });
        var view = new AtomicReference<AvailabilityPanel>();
        var done = new CountDownLatch(1);
        try {
            SwingUtilities.invokeAndWait(() -> {
                var panel = panel(new PreferencesFormMemory(node), (request, cancelled, progress) -> {
                    await(closeDelivered);
                    assertTrue(cancelled.getAsBoolean());
                    assertFalse(Thread.currentThread().isInterrupted());
                    cleanupEntered.countDown();
                    await(releaseCleanup);
                    return new ProbeResult(ProbeResult.Status.CANCELLED);
                });
                view.set(panel);
                field(panel, "did", JTextField.class).setText("FFFF-1-A");
                onStatus(panel, "Cancelled", done);
            });
            SwingUtilities.invokeLater(() -> field(view.get(), "check", JButton.class).doClick(0));
            assertTrue(flushEntered.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeLater(() -> { view.get().close(); closeDelivered.countDown(); });
            assertEquals(1, closeDelivered.getCount());
            releaseFlush.countDown();
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(field(view.get(), "check", JButton.class).isEnabled());
                assertEquals(1, done.getCount());
            });
            releaseCleanup.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally {
            releaseFlush.countDown();
            closeDelivered.countDown();
            releaseCleanup.countDown();
            close(view);
        }
    }

    private static AvailabilityPanel panel(FormMemory memory, ProbeRunner runner) {
        return new AvailabilityPanel(runner, new TestServerConfigurationProvider(), memory);
    }

    private static void onStatus(AvailabilityPanel panel, String expected, CountDownLatch done) {
        field(panel, "status", JLabel.class).addPropertyChangeListener("text", event -> {
            if (expected.equals(event.getNewValue())) { done.countDown(); }
        });
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Fixture was not released"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private static void close(AtomicReference<AvailabilityPanel> panel) throws Exception {
        if (panel.get() != null) { SwingUtilities.invokeAndWait(() -> panel.get().close()); }
    }
}
