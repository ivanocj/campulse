package com.ivanocj.cameras.availability.presentation.swing;

import com.ivanocj.cameras.availability.application.model.P2pInformation;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.testsupport.TestServerConfigurationProvider;

import static com.ivanocj.cameras.availability.presentation.swing.AvailabilityPanelTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ParallelBatchAvailabilityPanelTest {
    @TempDir
    Path directory;

    @Test
    void theDesktopDefaultsToEightProbesAndExposesABoundedAccessibleBatchOnlyControl() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new AvailabilityPanel((request, cancelled, progress) -> {
                throw new AssertionError("No probe was requested");
            }, new TestServerConfigurationProvider());
            var control = field(panel, "batchParallelism", JSpinner.class);
            var model = (SpinnerNumberModel) control.getModel();
            assertEquals(8, model.getNumber());
            assertEquals(1, model.getMinimum());
            assertEquals(16, model.getMaximum());
            assertTrue(control.isEnabled());
            assertSame(control, field(panel, "batchParallelismLabel", JLabel.class).getLabelFor());
            assertTrue(control.getToolTipText().contains("1"));
        });
    }

    @Test
    void defaultParallelScanningPublishesLaterRowsOnTheEdtBeforeSlowRowsAndPreservesP2pMetadata() throws Exception {
        var file = Files.writeString(directory.resolve("devices.txt"), codes(10));
        var entered = new CountDownLatch(8);
        var releaseEighth = new CountDownLatch(1);
        var releaseSlow = new CountDownLatch(1);
        var ninthEntered = new CountDownLatch(1);
        var published = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var view = new AtomicReference<AvailabilityPanel>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var panel = new AvailabilityPanel((request, cancelled, progress) -> {
                    assertFalse(SwingUtilities.isEventDispatchThread());
                    assertEquals(Duration.ofSeconds(9), request.timeout());
                    assertFalse(request.lanSearch());
                    calls.incrementAndGet();
                    int serial = serial(request.did().value());
                    if (serial <= 8) { entered.countDown(); }
                    if (serial == 9) { ninthEntered.countDown(); }
                    await(serial == 8 ? releaseEighth : releaseSlow);
                    return result(serial, ProbeResult.Status.REACHABLE_P2P, request.protocol());
                }, new TestServerConfigurationProvider());
                view.set(panel);
                panel.chooseBatchFile = () -> Optional.of(file);
                field(panel, "did", JTextField.class).setText("Previous DID");
                field(panel, "timeout", JSpinner.class).setValue(9);
                field(panel, "lanSearch", JCheckBox.class).setSelected(false);
                var table = field(panel, "batchResults", JTable.class);
                table.getModel().addTableModelListener(event -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    if (table.getModel().getRowCount() == 10
                            && "198.51.100.8:32108".equals(modelCell(table, 7, "Device IP / port"))) {
                        published.countDown();
                    }
                });
                finishOn(panel, "Batch complete", done);
                field(panel, "batchCheck", JButton.class).doClick();
                assertFalse(field(panel, "batchParallelism", JSpinner.class).isEnabled());
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS), "Eight devices should start without waiting for timeout");
            assertEquals(8, calls.get());
            releaseEighth.countDown();
            assertTrue(ninthEntered.await(2, TimeUnit.SECONDS));
            assertTrue(published.await(2, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                var panel = view.get();
                var progress = field(panel, "progress", JProgressBar.class);
                assertEquals(1, progress.getValue());
                assertEquals(10, progress.getMaximum());
                assertFalse(field(panel, "check", JButton.class).isEnabled());
                assertTrue(field(panel, "batchSummary", JLabel.class).getText().contains("8 parallel"));
            });
        } finally {
            releaseEighth.countDown();
            releaseSlow.countDown();
        }
        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertEquals(10, calls.get());
        SwingUtilities.invokeAndWait(() -> {
            var panel = view.get();
            var table = field(panel, "batchResults", JTable.class);
            assertEquals(10, table.getRowCount());
            table.getRowSorter().toggleSortOrder(1);
            table.getRowSorter().toggleSortOrder(1);
            int row = table.convertRowIndexToView(7);
            table.setRowSelectionInterval(row, row);
            assertEquals("198.51.100.8:32108", field(panel, "deviceEndpoint", JLabel.class).getText());
            assertTrue(field(panel, "p2pDetails", JTextArea.class).getText().startsWith("Device DID: FFFF-8-A\n"));
            assertEquals("Previous DID", field(panel, "did", JTextField.class).getText());
            assertTrue(field(panel, "batchParallelism", JSpinner.class).isEnabled());
            assertEquals(8, field(panel, "batchParallelism", JSpinner.class).getValue());
            assertTrue(field(panel, "batchSummary", JLabel.class).getText().contains("10 reachable"));
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationOrWindowCloseWaitsForAllParallelCleanupAndNeverStartsPendingRows(boolean closeWindow) throws Exception {
        var file = Files.writeString(directory.resolve("cancelled.txt"), codes(6));
        var entered = new CountDownLatch(3);
        var checkToken = new CountDownLatch(1);
        var cleanupEntered = new CountDownLatch(3);
        var releaseCleanup = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var tokens = new ConcurrentHashMap<String, BooleanSupplier>();
        var view = new AtomicReference<AvailabilityPanel>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var panel = new AvailabilityPanel((request, cancelled, progress) -> {
                    calls.incrementAndGet();
                    tokens.put(request.did().value(), cancelled);
                    entered.countDown();
                    await(checkToken);
                    assertTrue(cancelled.getAsBoolean());
                    cleanupEntered.countDown();
                    await(releaseCleanup);
                    assertFalse(Thread.currentThread().isInterrupted());
                    return result(serial(request.did().value()), ProbeResult.Status.CANCELLED, request.protocol());
                }, new TestServerConfigurationProvider());
                view.set(panel);
                field(panel, "batchParallelism", JSpinner.class).setValue(3);
                panel.chooseBatchFile = () -> Optional.of(file);
                finishOn(panel, "Batch cancelled", done);
                field(panel, "batchCheck", JButton.class).doClick();
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                if (closeWindow) { view.get().close(); }
                else { field(view.get(), "cancel", JButton.class).doClick(); }
                assertTrue(tokens.values().stream().allMatch(BooleanSupplier::getAsBoolean));
            });
            checkToken.countDown();
            assertTrue(cleanupEntered.await(2, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertFalse(field(view.get(), "batchParallelism", JSpinner.class).isEnabled());
                assertFalse(field(view.get(), "batchCheck", JButton.class).isEnabled());
                assertFalse(view.get().menuBar().getMenu(0).getItem(0).isEnabled());
                assertEquals(1L, done.getCount());
            });
            assertEquals(3, calls.get());
        } finally {
            if (view.get() != null) { SwingUtilities.invokeAndWait(() -> view.get().close()); }
            checkToken.countDown();
            releaseCleanup.countDown();
        }
        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertEquals(3, calls.get());
        SwingUtilities.invokeAndWait(() -> {
            var panel = view.get();
            var table = field(panel, "batchResults", JTable.class);
            assertEquals("Cancelled", modelCell(table, 0, "Result"));
            assertEquals("Cancelled", modelCell(table, 5, "Result"));
            assertTrue(modelCell(table, 3, "Details").toString().contains("Not tested"));
            table.setRowSelectionInterval(0, 0);
            assertEquals("198.51.100.1:32108", field(panel, "deviceEndpoint", JLabel.class).getText());
            table.setRowSelectionInterval(3, 3);
            assertEquals("", field(panel, "p2pDetails", JTextArea.class).getText());
            assertTrue(field(panel, "batchParallelism", JSpinner.class).isEnabled());
        });
    }

    private static void finishOn(AvailabilityPanel panel, String headline, CountDownLatch done) {
        field(panel, "status", JLabel.class).addPropertyChangeListener("text", event -> {
            if (headline.equals(event.getNewValue())) { done.countDown(); }
        });
    }

    private static Object modelCell(JTable table, int row, String column) {
        return table.getModel().getValueAt(row,
                table.convertColumnIndexToModel(table.getColumnModel().getColumnIndex(column)));
    }

    private static int serial(String did) {
        return Integer.parseInt(did.split("-")[1]);
    }

    private static ProbeResult result(int serial, ProbeResult.Status status, TransportProtocol protocol) {
        var device = new InetSocketAddress("198.51.100." + serial, 32108);
        var information = new P2pInformation(Optional.of(protocol), status == ProbeResult.Status.CANCELLED
                ? P2pInformation.ConnectionMode.NONE : P2pInformation.ConnectionMode.DIRECT_P2P,
                Optional.empty(), List.of(), List.of(), List.of(device), List.of(), Map.of());
        return new ProbeResult(status, "Probe information retained.", true,
                status == ProbeResult.Status.CANCELLED ? Optional.empty() : Optional.of(device),
                Optional.empty(), Duration.ofMillis(400), information);
    }

    private static String codes(int count) {
        var lines = new StringBuilder();
        for (int serial = 1; serial <= count; serial++) {
            lines.append("FFFF-").append(serial).append("-A\n");
        }
        return lines.toString();
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) { throw new IOException("Test did not release the probe"); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }
}
