package com.ivanocj.cameras.availability.presentation.swing;

import com.ivanocj.cameras.availability.application.model.P2pInformation;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.testsupport.TestServerConfigurationProvider;

import static com.ivanocj.cameras.availability.presentation.swing.AvailabilityPanelTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CancellationFeedbackTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void singleCancelAcknowledgesImmediatelyAndKeepsInputsLockedUntilCleanupReturns(boolean keyboard) throws Exception {
        var entered = new CountDownLatch(1);
        var beginCleanup = new CountDownLatch(1);
        var cleanupLogged = new CountDownLatch(1);
        var releaseCleanup = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var token = new AtomicReference<BooleanSupplier>();
        var view = new AtomicReference<AvailabilityPanel>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var panel = new AvailabilityPanel((request, cancelled, progress) -> {
                    token.set(cancelled);
                    entered.countDown();
                    await(beginCleanup);
                    assertTrue(cancelled.getAsBoolean());
                    progress.accept("Fixture cleanup started.");
                    await(releaseCleanup);
                    assertFalse(Thread.currentThread().isInterrupted());
                    var information = new P2pInformation(Optional.of(request.protocol()), P2pInformation.ConnectionMode.NONE,
                            Optional.empty(), List.of(), List.of(), List.of(new InetSocketAddress("198.51.100.8", 32108)),
                            List.of(), Map.of());
                    return new ProbeResult(ProbeResult.Status.CANCELLED, "Check cancelled.", false,
                            Optional.empty(), Optional.empty(), Duration.ofMillis(400), information);
                }, new TestServerConfigurationProvider());
                view.set(panel);
                onHeadline(panel, "Cancelled", done);
                onCleanupMessage(panel, cleanupLogged);
                field(panel, "did", JTextField.class).setText("FFFF-1-A");
                field(panel, "check", JButton.class).doClick(0);
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                cancel(view.get(), keyboard);
                assertTrue(token.get().getAsBoolean());
                assertCancellationPending(view.get());
                assertEquals("Single check · PPCS\nFFFF-1-A", field(view.get(), "resultContext", JTextArea.class).getText());
            });
            beginCleanup.countDown();
            assertTrue(cleanupLogged.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertCancellationPending(view.get());
                assertEquals(1L, done.getCount());
            });
            releaseCleanup.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("198.51.100.8:32108", field(view.get(), "deviceEndpoint", JLabel.class).getText());
                assertTrue(field(view.get(), "check", JButton.class).isEnabled());
                assertFalse(field(view.get(), "progress", JProgressBar.class).isVisible());
                assertFalse(field(view.get(), "cancel", JButton.class).getAction().isEnabled());
            });
        } finally {
            beginCleanup.countDown();
            releaseCleanup.countDown();
            close(view);
        }
    }

    @ParameterizedTest
    @CsvSource({"1,false", "1,true", "3,false", "3,true"})
    void batchProgressCannotMaskCancellationOrReleaseInputsWhileProbesAreCleaningUp(int parallelism, boolean keyboard) throws Exception {
        var file = Files.writeString(directory.resolve("cancelled.txt"), IntStream.rangeClosed(1, 6)
                .mapToObj(serial -> "FFFF-" + serial + "-A").collect(Collectors.joining("\n")));
        var entered = new CountDownLatch(parallelism);
        var beginCleanup = new CountDownLatch(1);
        var cleanupEntered = new CountDownLatch(parallelism);
        var cleanupLogged = new CountDownLatch(1);
        var releaseCleanup = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var view = new AtomicReference<AvailabilityPanel>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var panel = new AvailabilityPanel((request, cancelled, progress) -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    await(beginCleanup);
                    assertTrue(cancelled.getAsBoolean());
                    progress.accept("Fixture cleanup started.");
                    cleanupEntered.countDown();
                    await(releaseCleanup);
                    assertFalse(Thread.currentThread().isInterrupted());
                    return new ProbeResult(ProbeResult.Status.CANCELLED);
                }, new TestServerConfigurationProvider());
                view.set(panel);
                panel.chooseBatchFile = () -> Optional.of(file);
                field(panel, "batchParallelism", JSpinner.class).setValue(parallelism);
                onHeadline(panel, "Batch cancelled", done);
                onCleanupMessage(panel, cleanupLogged);
                field(panel, "batchCheck", JButton.class).doClick(0);
                // Hold the EDT briefly so BatchLoaded is queued, but cannot render before Cancel.
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                cancel(panel, keyboard);
                assertCancellationPending(panel);
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertCancellationPending(view.get());
                assertEquals("Batch summary · cancelled.txt", field(view.get(), "resultContext", JTextArea.class).getText());
            });
            beginCleanup.countDown();
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
            assertTrue(cleanupLogged.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertCancellationPending(view.get());
                assertEquals(1L, done.getCount());
                assertEquals(6, field(view.get(), "batchResults", JTable.class).getRowCount());
                assertFalse(view.get().menuBar().getMenu(0).getItem(0).isEnabled());
            });
            assertEquals(parallelism, calls.get());
            releaseCleanup.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(parallelism, calls.get(), "No pending device may start after cancellation");
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(field(view.get(), "check", JButton.class).isEnabled());
                assertTrue(field(view.get(), "batchSummary", JLabel.class).getText().contains("6 cancelled"));
                assertFalse(field(view.get(), "cancel", JButton.class).getAction().isEnabled());
                assertEquals("Batch summary · cancelled.txt", field(view.get(), "resultContext", JTextArea.class).getText());
            });
        } finally {
            beginCleanup.countDown();
            releaseCleanup.countDown();
            close(view);
        }
    }

    @Test
    void theBackendResultRemainsAuthoritativeAndTheNextCheckGetsAFreshToken() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var firstDone = new CountDownLatch(1);
        var nextDone = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var view = new AtomicReference<AvailabilityPanel>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var panel = new AvailabilityPanel((request, cancelled, progress) -> {
                    if (calls.incrementAndGet() == 1) {
                        entered.countDown();
                        await(release);
                        assertTrue(cancelled.getAsBoolean());
                        // Models a terminal decision already made before a late cancel request.
                        return new ProbeResult(ProbeResult.Status.REACHABLE_RELAY, "Relay confirmed.", true,
                                Optional.of(new InetSocketAddress("192.0.2.8", 32108)), Optional.empty(), Duration.ofMillis(400));
                    }
                    assertFalse(cancelled.getAsBoolean());
                    return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
                }, new TestServerConfigurationProvider());
                view.set(panel);
                onHeadline(panel, "Reachable · UDP relay", firstDone);
                onHeadline(panel, "Inconclusive", nextDone);
                field(panel, "did", JTextField.class).setText("FFFF-1-A");
                field(panel, "check", JButton.class).doClick(0);
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                field(view.get(), "cancel", JButton.class).doClick(0);
                assertCancellationPending(view.get());
            });
            release.countDown();
            assertTrue(firstDone.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("Not returned", field(view.get(), "deviceEndpoint", JLabel.class).getText());
                assertEquals("192.0.2.8:32108", field(view.get(), "peer", JLabel.class).getText());
                field(view.get(), "did", JTextField.class).setText("WWWW-2-B");
                field(view.get(), "check", JButton.class).doClick(0);
                assertEquals("Checking…", field(view.get(), "status", JLabel.class).getText());
            });
            assertTrue(nextDone.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("Single check · PPCS\nWWWW-2-B", field(view.get(), "resultContext", JTextArea.class).getText());
                assertTrue(field(view.get(), "check", JButton.class).isEnabled());
                assertEquals(2, calls.get());
            });
        } finally {
            release.countDown();
            close(view);
        }
    }

    private static void cancel(AvailabilityPanel panel, boolean keyboard) {
        var button = field(panel, "cancel", JButton.class);
        if (keyboard) {
            var binding = panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_PERIOD, InputEvent.CTRL_DOWN_MASK));
            assertNotNull(binding);
            var action = panel.getActionMap().get(binding);
            assertSame(button.getAction(), action);
            assertTrue(action.isEnabled());
            action.actionPerformed(new ActionEvent(panel, ActionEvent.ACTION_PERFORMED, binding.toString()));
        } else {
            button.doClick(0);
        }
    }

    private static void assertCancellationPending(AvailabilityPanel panel) {
        assertEquals("Cancellation requested", field(panel, "status", JLabel.class).getText());
        assertTrue(field(panel, "details", JTextArea.class).getText().contains("cleanup"));
        assertFalse(field(panel, "check", JButton.class).isEnabled());
        assertFalse(field(panel, "batchCheck", JButton.class).isEnabled());
        assertFalse(field(panel, "did", JTextField.class).isEnabled());
        assertTrue(field(panel, "progress", JProgressBar.class).isVisible());
    }

    private static void onHeadline(AvailabilityPanel panel, String headline, CountDownLatch done) {
        field(panel, "status", JLabel.class).addPropertyChangeListener("text", event -> {
            assertTrue(SwingUtilities.isEventDispatchThread());
            if (headline.equals(event.getNewValue())) { done.countDown(); }
        });
    }

    private static void onCleanupMessage(AvailabilityPanel panel, CountDownLatch logged) {
        var log = field(panel, "log", JTextArea.class);
        log.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) {
                assertTrue(SwingUtilities.isEventDispatchThread());
                if (log.getText().contains("Fixture cleanup started.")) { logged.countDown(); }
            }
            @Override public void removeUpdate(DocumentEvent event) { }
            @Override public void changedUpdate(DocumentEvent event) { }
        });
    }

    private static void close(AtomicReference<AvailabilityPanel> view) throws Exception {
        if (view.get() != null) { SwingUtilities.invokeAndWait(() -> view.get().close()); }
    }

    private static void await(CountDownLatch release) throws IOException {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) { throw new IOException("Test did not release the probe"); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }
}
