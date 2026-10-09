package com.ivanocj.cameras.availability.presentation.swing;

import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.testsupport.TestServerConfigurationProvider;
import static com.ivanocj.cameras.availability.presentation.swing.AvailabilityPanelTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BatchUpdateDeliveryTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aBurstYieldsToUiActionsAndSettlesEveryRowBeforeCompletionOrANewCheck(boolean cancelBetweenTurns) throws Exception {
        int rows = 256;
        var file = Files.writeString(directory.resolve("burst.txt"), IntStream.rangeClosed(1, rows)
                .mapToObj(serial -> "FFFF-" + serial + "-A").collect(Collectors.joining("\n")));
        var returned = new CountDownLatch(rows);
        var done = new CountDownLatch(1);
        var nextDone = new CountDownLatch(1);
        var pulseScheduled = new AtomicBoolean();
        var busyAtPulse = new AtomicBoolean();
        var processedAtPulse = new AtomicInteger();
        var updates = new AtomicInteger();
        var inserts = new AtomicInteger();
        var calls = new AtomicInteger();
        var unexpected = new AtomicReference<Throwable>();
        var view = new AtomicReference<AvailabilityPanel>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var panel = new AvailabilityPanel((request, cancelled, progress) -> {
                    assertFalse(SwingUtilities.isEventDispatchThread());
                    int serial = Integer.parseInt(request.did().value().split("-")[1]);
                    if (serial == 999) { assertFalse(cancelled.getAsBoolean(), "The next check needs a fresh token"); }
                    calls.incrementAndGet();
                    for (int message = 0; message < 10; message++) { progress.accept("stage " + message + " for " + serial); }
                    returned.countDown();
                    return new ProbeResult(ProbeResult.Status.REACHABLE_P2P, "Evidence " + serial, true,
                            Optional.empty(), Optional.empty(), Duration.ofMillis(serial));
                }, new TestServerConfigurationProvider());
                view.set(panel);
                panel.chooseBatchFile = () -> Optional.of(file);
                var table = field(panel, "batchResults", JTable.class);
                table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(11, SortOrder.DESCENDING)));
                field(panel, "batchOutcomeFilter", JComboBox.class).setSelectedItem("Reachable · direct P2P");
                table.getModel().addTableModelListener(event -> {
                    if (event.getLastRow() == Integer.MAX_VALUE) { return; }
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    updates.incrementAndGet();
                    if (pulseScheduled.compareAndSet(false, true)) {
                        SwingUtilities.invokeLater(() -> {
                            try {
                                busyAtPulse.set(!field(panel, "check", JButton.class).isEnabled());
                                processedAtPulse.set(field(panel, "progress", JProgressBar.class).getValue());
                                if (cancelBetweenTurns) {
                                    field(panel, "cancel", JButton.class).doClick(0);
                                    assertEquals("Cancellation requested", field(panel, "status", JLabel.class).getText());
                                }
                            } catch (Throwable failed) { unexpected.compareAndSet(null, failed); }
                        });
                    }
                });
                field(panel, "log", JTextArea.class).getDocument().addDocumentListener(new DocumentListener() {
                    @Override public void insertUpdate(DocumentEvent event) {
                        assertTrue(SwingUtilities.isEventDispatchThread());
                        inserts.incrementAndGet();
                    }
                    @Override public void removeUpdate(DocumentEvent event) { }
                    @Override public void changedUpdate(DocumentEvent event) { }
                });
                field(panel, "check", JButton.class).addPropertyChangeListener("enabled", event -> {
                    if (Boolean.TRUE.equals(event.getNewValue()) && calls.get() <= rows) {
                        try {
                            assertEquals(rows, table.getRowCount());
                            assertEquals("Batch complete", field(panel, "status", JLabel.class).getText());
                            var model = (BatchResultsTableModel) table.getModel();
                            for (int row = 0; row < rows; row++) { assertTrue(model.resultAt(row).isPresent()); }
                        } catch (Throwable failed) { unexpected.compareAndSet(null, failed); }
                        finally { done.countDown(); }
                    }
                });
                field(panel, "status", JLabel.class).addPropertyChangeListener("text", event -> {
                    if ("Reachable · direct P2P".equals(event.getNewValue())) { nextDone.countDown(); }
                });
                field(panel, "batchCheck", JButton.class).doClick(0);
                // Accumulate a complete burst without sleeps; no Swing callback can consume it yet.
                try { assertTrue(returned.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            });
            assertTrue(done.await(10, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertNull(unexpected.get());
                assertTrue(busyAtPulse.get(), "A queued UI action must run before the burst finishes");
                assertTrue(processedAtPulse.get() > 0 && processedAtPulse.get() < rows, "Delivery must yield between chunks");
                assertTrue(updates.get() < rows, "Coalesce table notifications, not result data");
                assertTrue(inserts.get() < rows, "Coalesce log document edits");
                var panel = view.get();
                var table = field(panel, "batchResults", JTable.class);
                var model = (BatchResultsTableModel) table.getModel();
                for (int row = 0; row < rows; row++) {
                    assertEquals("FFFF-" + (row + 1) + "-A", model.resultAt(row).orElseThrow().entry().code());
                    assertEquals("Evidence " + (row + 1), model.getValueAt(row, 10));
                    assertEquals((row + 1) + " ms", model.getValueAt(row, 11));
                }
                assertEquals(rows, table.getValueAt(0, 0));
                assertEquals(rows + " / " + rows + " shown", field(panel, "batchShownCount", JLabel.class).getText());
                assertTrue(field(panel, "batchSummary", JLabel.class).getText().contains(rows + " reachable"));
                var log = field(panel, "log", JTextArea.class);
                assertEquals(2000, log.getLineCount() - 1);
                assertTrue(log.getText().endsWith(field(panel, "details", JTextArea.class).getText() + "\n"));
                field(panel, "did", JTextField.class).setText("FFFF-999-A");
                field(panel, "check", JButton.class).doClick(0);
            });
            assertTrue(nextDone.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                var panel = view.get();
                assertEquals("Evidence 999", field(panel, "details", JTextArea.class).getText());
                assertEquals("Single check · PPCS\nFFFF-999-A", field(panel, "resultContext", JTextArea.class).getText());
                assertTrue(field(panel, "check", JButton.class).isEnabled());
                assertEquals(rows + 1, calls.get());
            });
        } finally {
            if (view.get() != null) { SwingUtilities.invokeAndWait(() -> view.get().close()); }
        }
    }
}
