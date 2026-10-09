package com.ivanocj.cameras.availability.presentation.swing;

import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.infrastructure.configuration.DefaultServerConfigurationProvider;
import com.ivanocj.cameras.availability.testsupport.TestServerConfigurationProvider;

import static com.ivanocj.cameras.availability.presentation.swing.AvailabilityPanelTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Captures request and result ownership before the one-screen UI is reorganized. */
class AvailabilityWorkflowCharacterizationTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @EnumSource(TransportProtocol.class)
    void singleUsesCustomServersWhileBatchRoutesTheSameIdentityThroughTheCatalog(TransportProtocol protocol) throws Exception {
        String identifier = switch (protocol) {
            case PPCS -> "WWWW-000001-A";
            case XQ -> "FFFF-000001-A";
            case GWELL -> "001234";
            case IOTVIDEO -> "Ab_01234";
        };
        String batchIdentifier = protocol.legacyUdp() ? identifier : protocol + ":" + identifier;
        String profileName = switch (protocol) {
            case PPCS -> "HIP2P · SY WWWW/TTTT/UUUU";
            case XQ -> "HIP2P · XQ FFFF/HHHH/GGGG";
            case GWELL -> "Yoosee · GWell remote presence (account required)";
            case IOTVIDEO -> "Yoosee · IoTVideo remote presence (account required)";
        };
        var configurations = new DefaultServerConfigurationProvider();
        var expectedProfile = configurations.profiles().stream()
                .filter(profile -> profile.name().equals(profileName)).findFirst().orElseThrow();
        var file = Files.writeString(directory.resolve("devices.csv"), "Serial,Description\n"
                + batchIdentifier + ",Câmera entrada\nMISSING-1-A,Unknown deployment\n");
        var singleDone = new CountDownLatch(1);
        var batchDone = new CountDownLatch(1);
        var requests = new CopyOnWriteArrayList<ProbeRequest>();
        var view = new AtomicReference<AvailabilityPanel>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var panel = new AvailabilityPanel((request, cancelled, progress) -> {
                    assertFalse(SwingUtilities.isEventDispatchThread());
                    requests.add(request);
                    return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
                }, configurations);
                view.set(panel);
                onHeadline(panel, "Inconclusive", singleDone);
                onHeadline(panel, "Batch complete", batchDone);
                panel.chooseBatchFile = () -> Optional.of(file);
                field(panel, "batchParallelism", JSpinner.class).setValue(1);
                field(panel, "timeout", JSpinner.class).setValue(9);
                field(panel, "lanSearch", JCheckBox.class).setSelected(false);
                field(panel, "servers", JTextArea.class).setText("192.0.2.40:45000");
                field(panel, "transport", JComboBox.class).setSelectedItem(protocol);
                field(panel, "did", JTextField.class).setText(identifier);
                for (var name : List.of("connectionSettings", "accountSettings", "evidence", "hichipTools", "diagnostics")) {
                    var toggle = field(panel, name + "Toggle", JToggleButton.class);
                    toggle.doClick(0);
                    toggle.doClick(0);
                }
                field(panel, "check", JButton.class).doClick(0);
            });
            assertTrue(singleDone.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(1, requests.size());
                assertEquals(List.of(InetSocketAddress.createUnresolved("192.0.2.40", 45000)), requests.getFirst().servers());
                assertEquals("Single check · " + protocol + "\n" + identifier,
                        field(view.get(), "resultContext", JTextArea.class).getText());
                assertTrue(field(view.get(), "check", JButton.class).isEnabled());
                field(view.get(), "batchCheck", JButton.class).doClick(0);
            });
            assertTrue(batchDone.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertEquals(2, requests.size(), "Unknown prefixes must be retained without starting a probe");
                assertEquals(expectedProfile.servers(), requests.getLast().servers());
                assertNotEquals(requests.getFirst().servers(), requests.getLast().servers());
                for (var request : requests) {
                    assertEquals(identifier, request.identifier().value(), "Preserve zero padding and key case");
                    assertEquals(protocol, request.protocol());
                    assertEquals(Duration.ofSeconds(9), request.timeout());
                    assertFalse(request.lanSearch());
                }
                var panel = view.get();
                assertEquals("Batch summary · devices.csv", field(panel, "resultContext", JTextArea.class).getText());
                assertEquals(identifier, field(panel, "did", JTextField.class).getText());
                assertEquals("192.0.2.40:45000", field(panel, "servers", JTextArea.class).getText());
                assertEquals(protocol, field(panel, "transport", JComboBox.class).getSelectedItem());
                var table = field(panel, "batchResults", JTable.class);
                assertEquals(2, table.getRowCount());
                assertEquals(batchIdentifier, cell(table, 0, "Device identifier"));
                assertEquals("Câmera entrada", cell(table, 0, "Description"));
                assertEquals("Skipped", cell(table, 1, "Result"));
                assertTrue(cell(table, 1, "Details").toString().contains("No known server profile"));
            });
        } finally {
            close(view);
        }
    }

    @Test
    void sortedBatchInspectionNeverChangesTheNextSingleRequestOrLeaksItsEvidenceDuringWork() throws Exception {
        var file = Files.writeString(directory.resolve("repeated.csv"), "Serial,Description\n"
                + "FFFF-1-A,First camera\nFFFF-1-A,Second camera\n");
        var initialDone = new CountDownLatch(1);
        var batchDone = new CountDownLatch(1);
        var nextEntered = new CountDownLatch(1);
        var releaseNext = new CountDownLatch(1);
        var nextDone = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var requests = new CopyOnWriteArrayList<ProbeRequest>();
        var view = new AtomicReference<AvailabilityPanel>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var panel = new AvailabilityPanel((request, cancelled, progress) -> {
                    assertFalse(SwingUtilities.isEventDispatchThread());
                    requests.add(request);
                    int call = calls.incrementAndGet();
                    if (call == 4) {
                        nextEntered.countDown();
                        await(releaseNext);
                    }
                    boolean relay = call == 3;
                    var peer = new InetSocketAddress(relay ? "192.0.2.8" : "198.51.100." + call, 32108);
                    return new ProbeResult(relay ? ProbeResult.Status.REACHABLE_RELAY : ProbeResult.Status.REACHABLE_P2P,
                            request.identifier().value() + " confirmed.", true, Optional.of(peer),
                            Optional.of(new InetSocketAddress("203.0.113.4", 55000)), Duration.ofMillis(400));
                }, new TestServerConfigurationProvider());
                view.set(panel);
                onHeadline(panel, "Reachable · direct P2P", initialDone);
                onHeadline(panel, "Batch complete", batchDone);
                panel.chooseBatchFile = () -> Optional.of(file);
                field(panel, "batchParallelism", JSpinner.class).setValue(1);
                field(panel, "did", JTextField.class).setText("WWWW-9-A");
                field(panel, "servers", JTextArea.class).setText("192.0.2.40:45000");
                field(panel, "check", JButton.class).doClick(0);
            });
            assertTrue(initialDone.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                assertEquals("198.51.100.1:32108", field(view.get(), "deviceEndpoint", JLabel.class).getText());
                assertEquals("Single check · PPCS\nWWWW-9-A", field(view.get(), "resultContext", JTextArea.class).getText());
                field(view.get(), "batchCheck", JButton.class).doClick(0);
            });
            assertTrue(batchDone.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                var panel = view.get();
                assertEquals("Batch summary · repeated.csv", field(panel, "resultContext", JTextArea.class).getText());
                assertEquals("", field(panel, "p2pDetails", JTextArea.class).getText());
                assertEquals("—", field(panel, "deviceEndpoint", JLabel.class).getText());
                var table = field(panel, "batchResults", JTable.class);
                int description = table.convertColumnIndexToModel(table.getColumnModel().getColumnIndex("Description"));
                table.getRowSorter().toggleSortOrder(description);
                table.getRowSorter().toggleSortOrder(description);
                table.setRowSelectionInterval(0, 0);
                assertEquals("Second camera", cell(table, 0, "Description"));
                assertEquals("Batch row · repeated.csv · line 3\nFFFF-1-A\nSecond camera",
                        field(panel, "resultContext", JTextArea.class).getText());
                assertEquals("Reachable · UDP relay", field(panel, "status", JLabel.class).getText());
                assertEquals("Not returned", field(panel, "deviceEndpoint", JLabel.class).getText());
                assertTrue(field(panel, "p2pDetails", JTextArea.class).getText().startsWith(
                        "Device DID: FFFF-1-A\nDescription: Second camera\n"));
                assertEquals("WWWW-9-A", field(panel, "did", JTextField.class).getText());
                onHeadline(panel, "Reachable · direct P2P", nextDone);
                field(panel, "did", JTextField.class).setText("WWWW-10-A");
                field(panel, "check", JButton.class).doClick(0);
                table.clearSelection();
                table.setRowSelectionInterval(1, 1);
                assertEquals("Checking…", field(panel, "status", JLabel.class).getText());
                assertEquals("Single check · PPCS\nWWWW-10-A", field(panel, "resultContext", JTextArea.class).getText());
                assertEquals("", field(panel, "p2pDetails", JTextArea.class).getText());
                assertEquals("—", field(panel, "deviceEndpoint", JLabel.class).getText());
                assertFalse(field(panel, "check", JButton.class).isEnabled());
            });
            assertTrue(nextEntered.await(5, TimeUnit.SECONDS));
            releaseNext.countDown();
            assertTrue(nextDone.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                var panel = view.get();
                assertEquals(List.of("WWWW-9-A", "FFFF-1-A", "FFFF-1-A", "WWWW-10-A"),
                        requests.stream().map(request -> request.identifier().value()).toList());
                assertEquals("198.51.100.4:32108", field(panel, "deviceEndpoint", JLabel.class).getText());
                assertEquals("WWWW-10-A confirmed.", field(panel, "details", JTextArea.class).getText());
                assertEquals("Single check · PPCS\nWWWW-10-A", field(panel, "resultContext", JTextArea.class).getText());
                assertFalse(field(panel, "p2pDetails", JTextArea.class).getText().contains("camera"));
                assertEquals("WWWW-10-A", field(panel, "did", JTextField.class).getText());
                assertTrue(field(panel, "check", JButton.class).isEnabled());
                field(panel, "did", JTextField.class).setText("not-a-did");
                assertEquals("Single check · PPCS\nWWWW-10-A", field(panel, "resultContext", JTextArea.class).getText(),
                        "Editing the form must not relabel the previous result");
                field(panel, "check", JButton.class).doClick(0);
                assertEquals("Single check · input not submitted", field(panel, "resultContext", JTextArea.class).getText());
                assertEquals("Invalid input", field(panel, "status", JLabel.class).getText());
                assertEquals(4, requests.size());
            });
        } finally {
            releaseNext.countDown();
            close(view);
        }
    }

    private static void onHeadline(AvailabilityPanel panel, String headline, CountDownLatch done) {
        field(panel, "status", JLabel.class).addPropertyChangeListener("text", event -> {
            if (headline.equals(event.getNewValue())) {
                assertTrue(SwingUtilities.isEventDispatchThread());
                done.countDown();
            }
        });
    }

    private static Object cell(JTable table, int row, String column) {
        return table.getValueAt(row, table.getColumnModel().getColumnIndex(column));
    }

    private static void close(AtomicReference<AvailabilityPanel> view) throws Exception {
        if (view.get() != null) {
            SwingUtilities.invokeAndWait(() -> view.get().close());
        }
    }

    private static void await(CountDownLatch release) throws IOException {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IOException("Test did not release the probe");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }
}
