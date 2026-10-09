package com.ivanocj.cameras.availability.presentation.swing;

import com.ivanocj.cameras.availability.application.model.P2pInformation;
import com.ivanocj.cameras.availability.application.model.P2pInformation.ConnectionMode;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.port.ProbeRunner;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.testsupport.TestServerConfigurationProvider;

import static com.ivanocj.cameras.availability.presentation.swing.AvailabilityPanelTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P2pAvailabilityPanelTest {
    private static final InetSocketAddress DEVICE = new InetSocketAddress("198.51.100.8", 32108);
    private static final InetSocketAddress SECOND_DEVICE = new InetSocketAddress("198.51.100.9", 32109);
    private static final InetSocketAddress RELAY = new InetSocketAddress("192.0.2.8", 57000);
    private static final InetSocketAddress SERVER = new InetSocketAddress("192.0.2.1", 32100);

    @TempDir
    Path directory;

    @Test
    void aSingleCheckDisplaysReturnedIpsAndSelectableP2pDetailsOnTheEdtEvenAfterATimeout() throws Exception {
        var done = new CountDownLatch(1);
        var view = new AtomicReference<AvailabilityPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel((request, cancelled, progress) -> result(ProbeResult.Status.TIMED_OUT,
                    request.protocol(), ConnectionMode.NONE, List.of(DEVICE), Optional.empty()),
                    "Not confirmed · timed out", done);
            view.set(panel);
            var information = field(panel, "p2pDetails", JTextArea.class);
            assertFalse(information.isEditable());
            information.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent event) {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                }
                @Override public void removeUpdate(DocumentEvent event) {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                }
                @Override public void changedUpdate(DocumentEvent event) { }
            });
            field(panel, "did", JTextField.class).setText("FFFF-1-A");
            field(panel, "check", JButton.class).doClick();
        });
        assertTrue(done.await(3, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            var panel = view.get();
            assertEquals("198.51.100.8:32108", field(panel, "deviceEndpoint", JLabel.class).getText());
            assertEquals("—", field(panel, "peer", JLabel.class).getText());
            assertEquals("Client public endpoint", field(panel, "publicEndpointLabel", JLabel.class).getText());
            var information = field(panel, "p2pDetails", JTextArea.class);
            assertTrue(information.getText().contains("Protocol: PPCS"));
            assertTrue(information.getText().contains("Responding servers: 192.0.2.1:32100"));
            assertTrue(information.getText().contains("Peer confirmation: Not confirmed"));
            assertEquals(0, information.getCaretPosition());
        });
    }

    @Test
    void batchRowsKeepIndependentIpsAndSortedSelectionDisplaysTheMatchingDevicesP2pInformation() throws Exception {
        var file = Files.writeString(directory.resolve("devices.csv"), "Serial,Description\n"
                + "FFFF-1-A,First camera\nFFFF-1-A,Second camera\nWWWW-2-B,Relay camera\nbroken,Invalid device");
        var results = List.of(result(ProbeResult.Status.TIMED_OUT, TransportProtocol.XQ, ConnectionMode.NONE,
                        List.of(DEVICE), Optional.empty()),
                result(ProbeResult.Status.REACHABLE_P2P, TransportProtocol.XQ, ConnectionMode.DIRECT_P2P,
                        List.of(SECOND_DEVICE), Optional.of(SECOND_DEVICE)),
                result(ProbeResult.Status.REACHABLE_RELAY, TransportProtocol.PPCS, ConnectionMode.UDP_RELAY,
                        List.of(), Optional.of(RELAY)));
        var calls = new AtomicInteger();
        var done = new CountDownLatch(1);
        var view = new AtomicReference<AvailabilityPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel((request, cancelled, progress) -> results.get(calls.getAndIncrement()), "Batch complete", done);
            view.set(panel);
            panel.chooseBatchFile = () -> Optional.of(file);
            field(panel, "did", JTextField.class).setText("Previous DID");
            field(panel, "batchCheck", JButton.class).doClick();
        });
        assertTrue(done.await(3, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            var panel = view.get();
            var table = field(panel, "batchResults", JTable.class);
            assertTrue(table.getColumnModel().getColumnIndex("Device IP / port")
                    < table.getColumnModel().getColumnIndex("Server profile"));
            assertEquals("198.51.100.8:32108", cell(table, 0, "Device IP / port"));
            assertEquals("198.51.100.9:32109", cell(table, 1, "Device IP / port"));
            assertEquals("198.51.100.9:32109", cell(table, 1, "Session endpoint"));
            assertEquals("Direct P2P", cell(table, 1, "Connection mode"));
            assertEquals("Not returned", cell(table, 2, "Device IP / port"));
            assertEquals("192.0.2.8:57000", cell(table, 2, "Session endpoint"));
            assertEquals("UDP relay", cell(table, 2, "Connection mode"));
            assertEquals("203.0.113.4:55000", cell(table, 2, "Client public endpoint"));
            assertEquals("—", cell(table, 3, "Device IP / port"));
            assertFalse(table.isCellEditable(0, table.getColumnModel().getColumnIndex("Device IP / port")));
            assertTrue(field(panel, "batchSummary", JLabel.class).getText().contains("2 reachable"));
            assertEquals("", field(panel, "p2pDetails", JTextArea.class).getText(), "A summary is not one device's evidence");

            table.getRowSorter().toggleSortOrder(table.getColumnModel().getColumnIndex("Description"));
            table.setRowSelectionInterval(table.convertRowIndexToView(0), table.convertRowIndexToView(0));
            assertEquals("198.51.100.8:32108", field(panel, "deviceEndpoint", JLabel.class).getText());
            assertTrue(field(panel, "p2pDetails", JTextArea.class).getText().startsWith(
                    "Device DID: FFFF-1-A\nDescription: First camera\n"));

            table.setRowSelectionInterval(table.convertRowIndexToView(1), table.convertRowIndexToView(1));
            assertEquals("198.51.100.9:32109", field(panel, "deviceEndpoint", JLabel.class).getText());
            assertTrue(field(panel, "p2pDetails", JTextArea.class).getText().contains("Description: Second camera"));

            table.setRowSelectionInterval(table.convertRowIndexToView(2), table.convertRowIndexToView(2));
            assertEquals("Not returned", field(panel, "deviceEndpoint", JLabel.class).getText());
            assertEquals("192.0.2.8:57000", field(panel, "peer", JLabel.class).getText());
            assertTrue(field(panel, "p2pDetails", JTextArea.class).getText().contains("The device IP was not returned"));
            assertFalse(field(panel, "p2pDetails", JTextArea.class).getText().contains("198.51.100.9"));

            table.setRowSelectionInterval(table.convertRowIndexToView(3), table.convertRowIndexToView(3));
            assertEquals("", field(panel, "p2pDetails", JTextArea.class).getText());
            assertEquals("—", field(panel, "deviceEndpoint", JLabel.class).getText());
            assertEquals("Previous DID", field(panel, "did", JTextField.class).getText());
        });
    }

    @Test
    void deviceIpsArePublishedInTheBatchTableBeforeLaterProbesFinish() throws Exception {
        var file = Files.writeString(directory.resolve("devices.txt"), "FFFF-1-A\nWWWW-2-B");
        var enteredSecond = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var published = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var view = new AtomicReference<AvailabilityPanel>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var panel = panel((request, cancelled, progress) -> {
                    if (request.protocol() == TransportProtocol.PPCS) {
                        enteredSecond.countDown();
                        try {
                            if (!release.await(3, TimeUnit.SECONDS)) {
                                throw new IOException("Test did not release the probe");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException(interrupted);
                        }
                    }
                    return result(ProbeResult.Status.TIMED_OUT, request.protocol(), ConnectionMode.NONE,
                            List.of(DEVICE), Optional.empty());
                }, "Batch complete", done);
                view.set(panel);
                panel.chooseBatchFile = () -> Optional.of(file);
                var table = field(panel, "batchResults", JTable.class);
                table.getModel().addTableModelListener(event -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    if (table.getModel().getRowCount() == 2 && "198.51.100.8:32108".equals(table.getModel().getValueAt(0,
                            table.convertColumnIndexToModel(table.getColumnModel().getColumnIndex("Device IP / port"))))) {
                        published.countDown();
                    }
                });
                field(panel, "batchCheck", JButton.class).doClick();
            });
            assertTrue(enteredSecond.await(2, TimeUnit.SECONDS));
            assertTrue(published.await(2, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("—", cell(field(view.get(), "batchResults", JTable.class), 1, "Device IP / port"));
                assertFalse(field(view.get(), "check", JButton.class).isEnabled());
            });
        } finally {
            release.countDown();
        }
        assertTrue(done.await(3, TimeUnit.SECONDS));
    }

    @Test
    void aCancelledProbeRetainsReturnedAddressesWhileUntestedRowsHaveNoNetworkInformation() throws Exception {
        var file = Files.writeString(directory.resolve("cancelled.txt"), "FFFF-1-A\nWWWW-2-B");
        var done = new CountDownLatch(1);
        var view = new AtomicReference<AvailabilityPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel((request, cancelled, progress) -> result(ProbeResult.Status.CANCELLED,
                    request.protocol(), ConnectionMode.NONE, List.of(DEVICE), Optional.empty()), "Batch cancelled", done);
            view.set(panel);
            panel.chooseBatchFile = () -> Optional.of(file);
            field(panel, "batchCheck", JButton.class).doClick();
        });
        assertTrue(done.await(3, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            var panel = view.get();
            var table = field(panel, "batchResults", JTable.class);
            assertEquals("198.51.100.8:32108", cell(table, 0, "Device IP / port"));
            assertEquals("—", cell(table, 1, "Device IP / port"));
            table.setRowSelectionInterval(0, 0);
            assertTrue(field(panel, "p2pDetails", JTextArea.class).getText().contains("198.51.100.8:32108"));
            table.setRowSelectionInterval(1, 1);
            assertEquals("", field(panel, "p2pDetails", JTextArea.class).getText());
            assertTrue(field(panel, "details", JTextArea.class).getText().contains("Not tested"));
        });
    }

    private static AvailabilityPanel panel(ProbeRunner runner, String headline, CountDownLatch done) {
        var panel = new AvailabilityPanel(runner, new TestServerConfigurationProvider());
        field(panel, "batchParallelism", JSpinner.class).setValue(1);
        field(panel, "status", JLabel.class).addPropertyChangeListener("text", event -> {
            if (headline.equals(event.getNewValue())) {
                done.countDown();
            }
        });
        return panel;
    }

    private static Object cell(JTable table, int row, String column) {
        return table.getValueAt(row, table.getColumnModel().getColumnIndex(column));
    }

    private static ProbeResult result(ProbeResult.Status status, TransportProtocol protocol, ConnectionMode mode,
                                      List<InetSocketAddress> devices, Optional<InetSocketAddress> peer) {
        var information = new P2pInformation(Optional.of(protocol), mode,
                Optional.of(new InetSocketAddress("192.0.2.50", 40000)), List.of(SERVER), List.of(SERVER), devices,
                mode == ConnectionMode.UDP_RELAY ? List.of(RELAY) : List.of(), Map.of(SERVER, 0));
        return new ProbeResult(status, "Probe evidence retained.", true, peer,
                Optional.of(new InetSocketAddress("203.0.113.4", 55000)), Duration.ofSeconds(1), information);
    }
}
