package com.ivanocj.cameras.availability.presentation.swing;

import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.port.ProbeRunner;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.testsupport.TestServerConfigurationProvider;

import static com.ivanocj.cameras.availability.presentation.swing.AvailabilityPanelTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.awt.Component;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BatchAvailabilityPanelTest {
    @TempDir
    Path directory;

    @Test
    void opensATextFileAndRoutesEachCodeWithoutChangingTheSingleCheckForm() throws Exception {
        var file = codes("# Devices\n ffff-158409-ebdac \nWWWW-1-A\nbroken\nMISSING-1-A\n");
        var done = new CountDownLatch(1);
        var requests = new ArrayList<ProbeRequest>();
        var ranOnEdt = new AtomicBoolean();
        var view = new AtomicReference<AvailabilityPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel((request, cancelled, progress) -> {
                requests.add(request);
                ranOnEdt.set(ranOnEdt.get() || SwingUtilities.isEventDispatchThread());
                progress.accept("Confirmed this code.");
                return new ProbeResult(ProbeResult.Status.REACHABLE_P2P);
            }, file, "Batch complete", done);
            view.set(panel);
            field(panel, "did", JTextField.class).setText("Previous DID");
            field(panel, "servers", JTextArea.class).setText("192.0.2.1:1234");
            field(panel, "timeout", JSpinner.class).setValue(9);
            field(panel, "lanSearch", JCheckBox.class).setSelected(false);
            assertFalse(field(panel, "batchCard", Component.class).isVisible());
            field(panel, "batchCheck", JButton.class).doClick();
            assertTrue(field(panel, "batchCard", Component.class).isVisible());
            assertFalse(field(panel, "batchCheck", JButton.class).isEnabled());
            assertFalse(field(panel, "check", JButton.class).isEnabled());
            assertFalse(panel.menuBar().getMenu(0).getItem(0).isEnabled());
            assertTrue(field(panel, "cancel", JButton.class).isEnabled());
        });
        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertFalse(ranOnEdt.get());
        assertEquals(List.of(TransportProtocol.XQ, TransportProtocol.PPCS),
                requests.stream().map(ProbeRequest::protocol).toList());
        assertTrue(requests.stream().allMatch(request -> request.timeout().equals(Duration.ofSeconds(9))
                && !request.lanSearch()));
        assertEquals(TestServerConfigurationProvider.profileNamed("HIP2P · XQ FFFF/HHHH/GGGG").servers(),
                requests.getFirst().servers());
        SwingUtilities.invokeAndWait(() -> {
            var panel = view.get();
            var table = field(panel, "batchResults", JTable.class);
            assertEquals(4, table.getRowCount());
            assertEquals(2, table.getValueAt(0, 0));
            assertEquals("FFFF-158409-EBDAC", table.getValueAt(0, 1));
            assertEquals("", cell(table, 0, "Description"));
            assertEquals("HIP2P · XQ FFFF/HHHH/GGGG", cell(table, 0, "Server profile"));
            assertEquals("XQ", cell(table, 0, "Protocol"));
            assertEquals("Reachable · direct P2P", cell(table, 0, "Result"));
            assertEquals("Skipped", cell(table, 2, "Result"));
            assertTrue(cell(table, 3, "Details").toString().contains("No known server profile"));
            assertFalse(table.isCellEditable(0, 1));
            assertEquals("Previous DID", field(panel, "did", JTextField.class).getText());
            assertEquals("192.0.2.1:1234", field(panel, "servers", JTextArea.class).getText());
            assertEquals(TransportProtocol.PPCS, field(panel, "transport", JComboBox.class).getSelectedItem());
            assertTrue(field(panel, "batchCheck", JButton.class).isEnabled());
            assertTrue(panel.menuBar().getMenu(0).getItem(0).isEnabled());
            assertTrue(field(panel, "batchSummary", JLabel.class).getText().contains("2 reachable"));
            assertTrue(field(panel, "log", JTextArea.class).getText().contains("[FFFF-158409-EBDAC]"));
        });
    }

    @Test
    void displaysIndividualResultsAndProgressOnTheEdtBeforeTheBatchEnds() throws Exception {
        var file = codes("FFFF-1-A\nWWWW-2-B");
        var rendered = new CountDownLatch(1);
        var enteredSecond = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var view = new AtomicReference<AvailabilityPanel>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var panel = panel((request, cancelled, progress) -> {
                    if (request.protocol() == TransportProtocol.PPCS) {
                        enteredSecond.countDown();
                        await(release);
                    }
                    return new ProbeResult(ProbeResult.Status.REACHABLE_RELAY);
                }, file, "Batch complete", done);
                view.set(panel);
                var table = field(panel, "batchResults", JTable.class);
                table.getModel().addTableModelListener(event -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    if (table.getModel().getRowCount() == 2
                            && "Reachable · UDP relay".equals(table.getModel().getValueAt(0,
                                    table.convertColumnIndexToModel(table.getColumnModel().getColumnIndex("Result"))))) {
                        rendered.countDown();
                    }
                });
                field(panel, "batchCheck", JButton.class).doClick();
            });
            assertTrue(enteredSecond.await(2, TimeUnit.SECONDS));
            assertTrue(rendered.await(2, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                var indicator = field(view.get(), "progress", JProgressBar.class);
                assertFalse(indicator.isIndeterminate());
                assertEquals(2, indicator.getMaximum());
                assertEquals(1, indicator.getValue());
                assertEquals("Queued", cell(field(view.get(), "batchResults", JTable.class), 1, "Result"));
            });
        } finally {
            release.countDown();
        }
        assertTrue(done.await(3, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellationKeepsInputsLockedUntilCleanupAndDoesNotStartTheNextCode(boolean closeWindow) throws Exception {
        var file = codes("FFFF-1-A\nWWWW-2-B");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var token = new AtomicReference<BooleanSupplier>();
        var calls = new ArrayList<ProbeRequest>();
        var view = new AtomicReference<AvailabilityPanel>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var panel = panel((request, cancelled, progress) -> {
                    calls.add(request);
                    token.set(cancelled);
                    entered.countDown();
                    await(release);
                    assertFalse(Thread.currentThread().isInterrupted());
                    return new ProbeResult(ProbeResult.Status.CANCELLED);
                }, file, "Batch cancelled", done);
                view.set(panel);
                field(panel, "batchCheck", JButton.class).doClick();
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                if (closeWindow) {
                    view.get().close();
                } else {
                    field(view.get(), "cancel", JButton.class).doClick();
                }
                assertTrue(token.get().getAsBoolean());
                assertFalse(field(view.get(), "batchCheck", JButton.class).isEnabled());
                assertFalse(field(view.get(), "check", JButton.class).isEnabled());
            });
        } finally {
            release.countDown();
        }
        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertEquals(1, calls.size());
        SwingUtilities.invokeAndWait(() -> {
            var table = field(view.get(), "batchResults", JTable.class);
            assertEquals("Cancelled", cell(table, 0, "Result"));
            assertEquals("Cancelled", cell(table, 1, "Result"));
            assertTrue(cell(table, 1, "Details").toString().contains("Not tested"));
            assertTrue(field(view.get(), "batchCheck", JButton.class).isEnabled());
        });
    }

    @Test
    void aNetworkFailureDoesNotLoseLaterResults() throws Exception {
        var file = codes("FFFF-1-A\nWWWW-2-B");
        var done = new CountDownLatch(1);
        var view = new AtomicReference<AvailabilityPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel((request, cancelled, progress) -> {
                if (request.protocol() == TransportProtocol.XQ) {
                    throw new IOException("Socket setup failed.");
                }
                return new ProbeResult(ProbeResult.Status.TIMED_OUT);
            }, file, "Batch complete", done);
            view.set(panel);
            field(panel, "batchCheck", JButton.class).doClick();
        });
        assertTrue(done.await(3, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            var table = field(view.get(), "batchResults", JTable.class);
            assertEquals("Check failed", cell(table, 0, "Result"));
            assertEquals("Socket setup failed.", cell(table, 0, "Details"));
            assertEquals("Not confirmed · timed out", cell(table, 1, "Result"));
        });
    }

    @Test
    void dismissingTheFileChooserDoesNotStartAJob() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new AvailabilityPanel(unusedRunner(), new TestServerConfigurationProvider());
            panel.chooseBatchFile = Optional::empty;
            field(panel, "batchCheck", JButton.class).doClick();
            assertEquals("Ready to check", field(panel, "status", JLabel.class).getText());
            assertTrue(field(panel, "check", JButton.class).isEnabled());
        });
    }

    @Test
    void explainsAnEmptyFileAndUnlocksTheForm() throws Exception {
        var done = new CountDownLatch(1);
        var view = new AtomicReference<AvailabilityPanel>();
        var file = codes("\n# empty list\n");
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel(unusedRunner(), file, "Invalid input", done);
            view.set(panel);
            field(panel, "batchCheck", JButton.class).doClick();
        });
        assertTrue(done.await(3, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(field(view.get(), "details", JTextArea.class).getText().contains("no device codes"));
            assertTrue(field(view.get(), "batchCheck", JButton.class).isEnabled());
            assertEquals(0, field(view.get(), "batchResults", JTable.class).getRowCount());
        });
    }

    @Test
    void reportsFileReadErrorsWithoutStartingNetworkWork() throws Exception {
        var done = new CountDownLatch(1);
        var view = new AtomicReference<AvailabilityPanel>();
        var missing = directory.resolve("missing.txt");
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel(unusedRunner(), missing, "Check failed", done);
            view.set(panel);
            field(panel, "batchCheck", JButton.class).doClick();
        });
        assertTrue(done.await(3, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(field(view.get(), "details", JTextArea.class).getText().contains("missing.txt"));
            assertTrue(field(view.get(), "check", JButton.class).isEnabled());
            assertEquals(0, field(view.get(), "batchResults", JTable.class).getRowCount());
        });
    }

    @Test
    void aSingleCheckAlsoLocksTheBatchAction() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new AvailabilityPanel((request, cancelled, progress) ->
                    new ProbeResult(ProbeResult.Status.INCONCLUSIVE), new TestServerConfigurationProvider());
            field(panel, "did", JTextField.class).setText("FFFF-1-A");
            field(panel, "check", JButton.class).doClick();
            assertFalse(field(panel, "batchCheck", JButton.class).isEnabled());
            assertFalse(field(panel, "batchParallelism", JSpinner.class).isEnabled());
        });
    }

    @Test
    void selectingASortedResultShowsThatCodesCompleteReachabilityMetadata() throws Exception {
        var file = codes("FFFF-1-A\nWWWW-2-B");
        var done = new CountDownLatch(1);
        var view = new AtomicReference<AvailabilityPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel((request, cancelled, progress) -> new ProbeResult(ProbeResult.Status.REACHABLE_RELAY,
                    request.did().value() + " confirmed.", true,
                    Optional.of(new InetSocketAddress("127.0.0.1", 32108)),
                    Optional.of(new InetSocketAddress("203.0.113.4", 55000)), Duration.ofMillis(400)),
                    file, "Batch complete", done);
            view.set(panel);
            field(panel, "batchCheck", JButton.class).doClick();
        });
        assertTrue(done.await(3, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            var panel = view.get();
            var table = field(panel, "batchResults", JTable.class);
            table.getRowSorter().toggleSortOrder(1);
            table.getRowSorter().toggleSortOrder(1);
            assertEquals("WWWW-2-B", table.getValueAt(0, 1));
            table.setRowSelectionInterval(1, 1);
            assertEquals("FFFF-1-A confirmed.", field(panel, "details", JTextArea.class).getText());
            assertEquals("Reachable · UDP relay", field(panel, "status", JLabel.class).getText());
            assertEquals("Server responded", field(panel, "network", JLabel.class).getText());
            assertEquals("127.0.0.1:32108", field(panel, "peer", JLabel.class).getText());
            assertEquals("203.0.113.4:55000", field(panel, "publicEndpoint", JLabel.class).getText());
            assertEquals("400 ms", field(panel, "elapsed", JLabel.class).getText());
            assertTrue(field(panel, "batchSummary", JLabel.class).getText().contains("2 reachable"));
        });
    }

    @Test
    void aNewBatchReplacesThePreviousFilesRows() throws Exception {
        var first = codes("FFFF-1-A\nWWWW-2-B");
        var second = Files.writeString(directory.resolve("next.txt"), "TTTT-3-C");
        var done = new AtomicReference<>(new CountDownLatch(1));
        var view = new AtomicReference<AvailabilityPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = new AvailabilityPanel((request, cancelled, progress) ->
                    new ProbeResult(ProbeResult.Status.INCONCLUSIVE), new TestServerConfigurationProvider());
            view.set(panel);
            panel.chooseBatchFile = () -> Optional.of(first);
            field(panel, "status", JLabel.class).addPropertyChangeListener("text", event -> {
                if ("Batch complete".equals(event.getNewValue())) {
                    done.get().countDown();
                }
            });
            field(panel, "batchCheck", JButton.class).doClick();
        });
        assertTrue(done.get().await(3, TimeUnit.SECONDS));
        done.set(new CountDownLatch(1));
        SwingUtilities.invokeAndWait(() -> {
            view.get().chooseBatchFile = () -> Optional.of(second);
            field(view.get(), "batchCheck", JButton.class).doClick();
        });
        assertTrue(done.get().await(3, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            var table = field(view.get(), "batchResults", JTable.class);
            assertEquals(1, table.getRowCount());
            assertEquals("TTTT-3-C", table.getValueAt(0, 1));
            assertEquals("Inconclusive", cell(table, 0, "Result"));
        });
    }

    @Test
    void opensCsvFromTheFileMenuAndDisplaysDescriptionsForEveryOutcome() throws Exception {
        var file = Files.writeString(directory.resolve("devices.CSV"), "Serial,Description\n"
                + "FFFF-000009-DEMO,\"Front door, north\"\nWWWW-000002-B,Câmera entrada\n"
                + "NNNN-000014-DEMO,Unknown deployment\nbroken,Invalid serial");
        var done = new CountDownLatch(1);
        var view = new AtomicReference<AvailabilityPanel>();
        var requests = new ArrayList<ProbeRequest>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel((request, cancelled, progress) -> {
                requests.add(request);
                return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
            }, file, "Batch complete", done);
            view.set(panel);
            var menu = panel.menuBar().getMenu(0);
            assertEquals("File", menu.getText());
            var open = menu.getItem(0);
            assertEquals("Open batch file & test…", open.getText());
            assertNotNull(open.getAccelerator());
            assertSame(field(panel, "batchCheck", JButton.class).getAction(), open.getAction());
            open.doClick();
            assertFalse(open.isEnabled());
            assertFalse(field(panel, "batchCheck", JButton.class).isEnabled());
        });
        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertEquals(List.of("FFFF-000009-DEMO", "WWWW-000002-B"),
                requests.stream().map(request -> request.did().value()).toList());
        assertEquals(List.of(TransportProtocol.XQ, TransportProtocol.PPCS),
                requests.stream().map(ProbeRequest::protocol).toList());
        SwingUtilities.invokeAndWait(() -> {
            var table = field(view.get(), "batchResults", JTable.class);
            assertEquals(4, table.getRowCount());
            assertEquals(12, table.getColumnCount());
            assertEquals(List.of("Front door, north", "Câmera entrada", "Unknown deployment", "Invalid serial"),
                    java.util.stream.IntStream.range(0, table.getRowCount())
                            .mapToObj(row -> cell(table, row, "Description")).toList());
            assertEquals("Skipped", cell(table, 2, "Result"));
            assertTrue(cell(table, 2, "Details").toString().contains("prefix NNNN"));
            assertTrue(view.get().menuBar().getMenu(0).getItem(0).isEnabled());
        });
    }

    @Test
    void anInvalidCsvHeaderIsReportedBeforeNetworkTesting() throws Exception {
        var file = Files.writeString(directory.resolve("invalid.csv"), "Serial,Name\nFFFF-1-A,Front door");
        var done = new CountDownLatch(1);
        var view = new AtomicReference<AvailabilityPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel(unusedRunner(), file, "Invalid input", done);
            view.set(panel);
            field(panel, "batchCheck", JButton.class).doClick();
        });
        assertTrue(done.await(3, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(field(view.get(), "details", JTextArea.class).getText().contains("Serial and Description"));
            assertEquals(0, field(view.get(), "batchResults", JTable.class).getRowCount());
            assertTrue(view.get().menuBar().getMenu(0).getItem(0).isEnabled());
        });
    }

    @Test
    void inspectingSkippedRowsShowsTheirSourceIdentityWithoutClaimingAProbeRan() throws Exception {
        var file = Files.writeString(directory.resolve("untested.txt"), "broken\nMISSING-1-A\n");
        var done = new CountDownLatch(1);
        var view = new AtomicReference<AvailabilityPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel(unusedRunner(), file, "Batch complete", done);
            view.set(panel);
            field(panel, "did", JTextField.class).setText("Previous input");
            field(panel, "batchCheck", JButton.class).doClick(0);
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            var panel = view.get();
            assertEquals("Batch summary · untested.txt", field(panel, "resultContext", JTextArea.class).getText());
            var table = field(panel, "batchResults", JTable.class);
            table.setRowSelectionInterval(1, 1);
            assertEquals("Batch row · untested.txt · line 2\nMISSING-1-A", field(panel, "resultContext", JTextArea.class).getText());
            assertEquals("Skipped", field(panel, "status", JLabel.class).getText());
            assertEquals("—", field(panel, "deviceEndpoint", JLabel.class).getText());
            assertEquals("Previous input", field(panel, "did", JTextField.class).getText());
        });
    }

    private static Object cell(JTable table, int row, String column) {
        return table.getValueAt(row, table.getColumnModel().getColumnIndex(column));
    }

    private Path codes(String content) throws IOException {
        return Files.writeString(directory.resolve("devices.txt"), content);
    }

    private static AvailabilityPanel panel(ProbeRunner runner, Path file, String finalHeadline, CountDownLatch done) {
        var panel = new AvailabilityPanel(runner, new TestServerConfigurationProvider());
        // These compatibility scenarios assert sequential ordering/cancellation; parallel behavior has its own tests.
        field(panel, "batchParallelism", JSpinner.class).setValue(1);
        panel.chooseBatchFile = () -> Optional.of(file);
        field(panel, "status", JLabel.class).addPropertyChangeListener("text", event -> {
            if (finalHeadline.equals(event.getNewValue())) {
                assertTrue(SwingUtilities.isEventDispatchThread());
                done.countDown();
            }
        });
        return panel;
    }

    private static ProbeRunner unusedRunner() {
        return (request, cancelled, progress) -> {
            throw new AssertionError("No probe should be started");
        };
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new IOException("Test did not release the probe");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }
}
