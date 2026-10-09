package com.ivanocj.cameras.availability.presentation.swing;

import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.port.ProbeRunner;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.presentation.AvailabilityPresenter;
import com.ivanocj.cameras.availability.testsupport.DeviceFixtures;
import com.ivanocj.cameras.availability.testsupport.TestServerConfigurationProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.JRootPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JSpinner;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.junit.jupiter.api.Test;

class AvailabilityPanelTest {
    @Test
    void submitsTheUsersDidOnAWorkerThread() throws Exception {
        var called = new CountDownLatch(1);
        var request = new AtomicReference<ProbeRequest>();
        var ranOnEdt = new AtomicBoolean(true);
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) -> {
                request.set(input);
                ranOnEdt.set(SwingUtilities.isEventDispatchThread());
                called.countDown();
                return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
            });
            field(panel, "did", JTextField.class).setText(" ffff-158409-ebdac ");
            field(panel, "servers", JTextArea.class).setText("127.0.0.1:32100");
            field(panel, "check", JButton.class).doClick();
        });
        assertTrue(called.await(2, TimeUnit.SECONDS));
        assertEquals("FFFF-158409-EBDAC", request.get().did().value());
        assertFalse(ranOnEdt.get());
    }

    @Test
    void rendersTheCompletedResultOnTheEventDispatchThread() throws Exception {
        var rendered = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) ->
                    new ProbeResult(ProbeResult.Status.REACHABLE_P2P));
            var status = field(panel, "status", JLabel.class);
            status.addPropertyChangeListener("text", event -> {
                if ("Reachable · direct P2P".equals(event.getNewValue())) {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    rendered.countDown();
                }
            });
            field(panel, "did", JTextField.class).setText("FFFF-158409-EBDAC");
            field(panel, "servers", JTextArea.class).setText("127.0.0.1");
            field(panel, "check", JButton.class).doClick();
        });
        assertTrue(rendered.await(2, TimeUnit.SECONDS));
    }

    @Test
    void locksTheProbeInputsWhileADeviceCheckIsRunning() throws Exception {
        var release = new CountDownLatch(1);
        try {
            SwingUtilities.invokeAndWait(() -> {
                var panel = newPanel((input, cancelled, progress) -> {
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException(interrupted);
                    }
                    return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
                });
                var did = field(panel, "did", JTextField.class);
                var servers = field(panel, "servers", JTextArea.class);
                var check = field(panel, "check", JButton.class);
                did.setText("FFFF-158409-EBDAC");
                servers.setText("127.0.0.1");
                check.doClick();
                assertTrue(List.of(did, servers, check).stream().noneMatch(Component::isEnabled));
            });
        } finally {
            release.countDown();
        }
    }

    @Test
    void cancelSignalsTheRunningProbeWithoutInterruptingItsCleanup() throws Exception {
        var entered = new CountDownLatch(1);
        var cancellation = new AtomicReference<BooleanSupplier>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) -> {
                cancellation.set(cancelled);
                entered.countDown();
                return new ProbeResult(ProbeResult.Status.CANCELLED);
            });
            field(panel, "did", JTextField.class).setText("FFFF-158409-EBDAC");
            field(panel, "servers", JTextArea.class).setText("127.0.0.1");
            field(panel, "check", JButton.class).doClick();
            field(panel, "cancel", JButton.class).doClick();
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(cancellation.get().getAsBoolean());
    }

    @Test
    void explainsInvalidInputWithoutThrowingOnTheUiThread() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) -> {
                throw new AssertionError("Invalid DID must never reach the transport");
            });
            field(panel, "did", JTextField.class).setText("not-a-did");
            field(panel, "servers", JTextArea.class).setText("127.0.0.1");
            field(panel, "check", JButton.class).doClick();
            assertEquals("Invalid input", field(panel, "status", JLabel.class).getText());
            assertTrue(field(panel, "details", JTextArea.class).getText().contains("PREFIX-SERIAL-CODE"));
        });
    }

    @Test
    void showsTheActualNetworkFailureAndRestoresTheForm() throws Exception {
        var rendered = new CountDownLatch(1);
        var view = new AtomicReference<AvailabilityPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) -> {
                throw new IOException("Could not resolve any PPCS IPv4 server.");
            });
            view.set(panel);
            field(panel, "status", JLabel.class).addPropertyChangeListener("text", event -> {
                if ("Check failed".equals(event.getNewValue())) {
                    rendered.countDown();
                }
            });
            field(panel, "did", JTextField.class).setText("FFFF-158409-EBDAC");
            field(panel, "servers", JTextArea.class).setText("missing.example");
            field(panel, "check", JButton.class).doClick();
        });
        assertTrue(rendered.await(2, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(field(view.get(), "details", JTextArea.class).getText().contains("Could not resolve"));
            assertTrue(field(view.get(), "check", JButton.class).isEnabled());
            assertEquals("Not confirmed", field(view.get(), "network", JLabel.class).getText());
        });
    }

    @Test
    void honorsTheUsersTimeoutAndLanDiscoverySettings() throws Exception {
        var submitted = new CountDownLatch(1);
        var request = new AtomicReference<ProbeRequest>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) -> {
                request.set(input);
                submitted.countDown();
                return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
            });
            field(panel, "did", JTextField.class).setText("FFFF-158409-EBDAC");
            field(panel, "servers", JTextArea.class).setText("127.0.0.1");
            field(panel, "timeout", JSpinner.class).setValue(5);
            field(panel, "lanSearch", JCheckBox.class).setSelected(false);
            field(panel, "check", JButton.class).doClick();
        });
        assertTrue(submitted.await(2, TimeUnit.SECONDS));
        assertEquals(Duration.ofSeconds(5), request.get().timeout());
        assertFalse(request.get().lanSearch());
    }

    @Test
    void buildsAHiChipDidWithTheConvertedUidAlgorithm() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) ->
                    new ProbeResult(ProbeResult.Status.INCONCLUSIVE));
            field(panel, "uidPrefix", JTextField.class).setText("ffff");
            field(panel, "uidSerial", JTextField.class).setText("158409");
            field(panel, "uidGenerate", JButton.class).doClick();
            assertEquals("FFFF-158409-EBDAC", field(panel, "did", JTextField.class).getText());
            assertEquals("No check submitted", field(panel, "resultContext", JTextArea.class).getText());
        });
    }

    @Test
    void explainsAnInvalidHiChipSerialInline() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) ->
                    new ProbeResult(ProbeResult.Status.INCONCLUSIVE));
            field(panel, "uidSerial", JTextField.class).setText("abc");
            field(panel, "uidGenerate", JButton.class).doClick();
            assertEquals("Invalid input", field(panel, "status", JLabel.class).getText());
            assertEquals("HiChip DID builder", field(panel, "resultContext", JTextArea.class).getText());
        });
    }

    @Test
    void showsRelayModeSeparatelyFromServerHealthAndPeerMetadata() throws Exception {
        var rendered = new CountDownLatch(1);
        var view = new AtomicReference<AvailabilityPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) -> new ProbeResult(
                    ProbeResult.Status.REACHABLE_RELAY, "DID-matched peer confirmed.", true,
                    Optional.of(new InetSocketAddress("127.0.0.1", 32108)),
                    Optional.of(new InetSocketAddress("203.0.113.4", 55000)), Duration.ofMillis(400)));
            view.set(panel);
            field(panel, "status", JLabel.class).addPropertyChangeListener("text", event -> {
                if ("Reachable · UDP relay".equals(event.getNewValue())) {
                    rendered.countDown();
                }
            });
            field(panel, "did", JTextField.class).setText("FFFF-158409-EBDAC");
            field(panel, "servers", JTextArea.class).setText("127.0.0.1");
            field(panel, "check", JButton.class).doClick();
        });
        assertTrue(rendered.await(2, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> assertEquals(
                List.of("Server responded", "127.0.0.1:32108", "203.0.113.4:55000", "400 ms", "DID-matched peer confirmed."),
                List.of(field(view.get(), "network", JLabel.class).getText(),
                        field(view.get(), "peer", JLabel.class).getText(),
                        field(view.get(), "publicEndpoint", JLabel.class).getText(),
                        field(view.get(), "elapsed", JLabel.class).getText(),
                        field(view.get(), "details", JTextArea.class).getText())));
    }

    @Test
    void separatesConfigurationAndResultsIntoIndependentCards() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) ->
                    new ProbeResult(ProbeResult.Status.INCONCLUSIVE));
            assertNotSame(field(panel, "did", JTextField.class).getParent(),
                    field(panel, "status", JLabel.class).getParent());
        });
    }

    @Test
    void labelsEveryConfigurationInputForKeyboardAndScreenReaderUsers() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) ->
                    new ProbeResult(ProbeResult.Status.INCONCLUSIVE));
            assertTrue(List.of("did", "servers", "timeout", "uidPrefix", "uidSerial").stream().allMatch(name -> {
                var label = search(panel, name + "Label", JLabel.class);
                return label != null && label.getLabelFor() == field(panel, name, Component.class);
            }));
        });
    }

    @Test
    void offersTheBellReferenceServersForADidOnlyFirstRun() throws Exception {
        var submitted = new CountDownLatch(1);
        var request = new AtomicReference<ProbeRequest>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) -> {
                request.set(input);
                submitted.countDown();
                return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
            });
            field(panel, "did", JTextField.class).setText("FFFF-158409-EBDAC");
            field(panel, "check", JButton.class).doClick();
        });
        assertTrue(submitted.await(2, TimeUnit.SECONDS));
        assertEquals(List.of("one.object-camera.com", "174.129.34.188", "four.object-camera.com"),
                request.get().servers().stream().map(InetSocketAddress::getHostString).toList());
    }

    @Test
    void streamsReadOnlyDiagnosticsOnTheEventDispatchThread() throws Exception {
        var logged = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) -> {
                progress.accept("Connecting by DID...");
                return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
            });
            var log = field(panel, "log", JTextArea.class);
            assertFalse(log.isEditable());
            log.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent event) {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    if (log.getText().contains("Connecting by DID...")) {
                        logged.countDown();
                    }
                }
                @Override public void removeUpdate(DocumentEvent event) { }
                @Override public void changedUpdate(DocumentEvent event) { }
            });
            field(panel, "did", JTextField.class).setText("FFFF-158409-EBDAC");
            field(panel, "check", JButton.class).doClick();
        });
        assertTrue(logged.await(2, TimeUnit.SECONDS));
    }

    @Test
    void givesTheApplicationAClearReadableVisualHeading() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) ->
                    new ProbeResult(ProbeResult.Status.INCONCLUSIVE));
            var heading = field(panel, "heading", JLabel.class);
            assertEquals("Device availability", heading.getText());
            assertTrue(heading.getFont().getSize() >= 28);
        });
    }

    @Test
    void showsAnActiveCheckingStateInsteadOfThePreviousResult() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) ->
                    new ProbeResult(ProbeResult.Status.INCONCLUSIVE));
            field(panel, "did", JTextField.class).setText("FFFF-158409-EBDAC");
            field(panel, "check", JButton.class).doClick();
            assertEquals("Checking…", field(panel, "status", JLabel.class).getText());
            var indicator = field(panel, "progress", JProgressBar.class);
            assertTrue(indicator.isVisible() && indicator.isIndeterminate());
        });
    }

    @Test
    void closingTheViewSignalsCancellationToTheProbe() throws Exception {
        var entered = new CountDownLatch(1);
        var cancellation = new AtomicReference<BooleanSupplier>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) -> {
                cancellation.set(cancelled);
                entered.countDown();
                return new ProbeResult(ProbeResult.Status.CANCELLED);
            });
            field(panel, "did", JTextField.class).setText("FFFF-158409-EBDAC");
            field(panel, "check", JButton.class).doClick();
            panel.close();
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(cancellation.get().getAsBoolean());
    }

    @Test
    void makesThePrimaryActionVisuallyDistinctAndLegible() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) ->
                    new ProbeResult(ProbeResult.Status.INCONCLUSIVE));
            var check = field(panel, "check", JButton.class);
            assertNotEquals(field(panel, "cancel", JButton.class).getBackground(), check.getBackground());
            assertTrue(check.getForeground().getRed() < 60 && check.getBackground().getRed() > 130);
        });
    }

    @Test
    void identifiesTheImplementedProtocolProfile() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) ->
                    new ProbeResult(ProbeResult.Status.INCONCLUSIVE));
            assertEquals("Legacy PPCS · IPv4 UDP · direct / LAN / UDP relay",
                    field(panel, "profile", JLabel.class).getText());
        });
    }

    @Test
    void aNewCheckClearsThePreviousChecksNetworkAndPeerMetadata() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) ->
                    new ProbeResult(ProbeResult.Status.INCONCLUSIVE));
            field(panel, "network", JLabel.class).setText("Server responded");
            field(panel, "peer", JLabel.class).setText("203.0.113.1:32108");
            field(panel, "publicEndpoint", JLabel.class).setText("203.0.113.4:55000");
            field(panel, "elapsed", JLabel.class).setText("400 ms");
            field(panel, "details", JTextArea.class).setText("Previous device was confirmed.");
            field(panel, "deviceEndpoint", JLabel.class).setText("198.51.100.8:32108");
            field(panel, "p2pDetails", JTextArea.class).setText("Previous P2P information");
            field(panel, "did", JTextField.class).setText("FFFF-158409-EBDAC");
            field(panel, "check", JButton.class).doClick();
            assertEquals(List.of("Checking…", "—", "—", "—", ""), List.of(
                    field(panel, "network", JLabel.class).getText(), field(panel, "peer", JLabel.class).getText(),
                    field(panel, "publicEndpoint", JLabel.class).getText(), field(panel, "elapsed", JLabel.class).getText(),
                    field(panel, "details", JTextArea.class).getText()));
            assertEquals("—", field(panel, "deviceEndpoint", JLabel.class).getText());
            assertEquals("", field(panel, "p2pDetails", JTextArea.class).getText());
        });
    }

    @Test
    void aRecoveredServerProfilePopulatesTheEditableServerList() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) ->
                    new ProbeResult(ProbeResult.Status.INCONCLUSIVE));
            field(panel, "serverProfile", JComboBox.class).setSelectedIndex(1);
            assertEquals("47.91.222.210:32100,47.93.34.139:32100,47.254.79.165:32100",
                    field(panel, "servers", JTextArea.class).getText());
        });
    }

    @Test
    void theKnownServerSelectorIsLockedDuringAProbe() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) ->
                    new ProbeResult(ProbeResult.Status.INCONCLUSIVE));
            field(panel, "did", JTextField.class).setText("WWWW-158409-EBDAC");
            field(panel, "check", JButton.class).doClick();
            assertFalse(field(panel, "serverProfile", JComboBox.class).isEnabled());
        });
    }

    @Test
    void selectingAnXqProfileSubmitsAnXqRequest() throws Exception {
        var submitted = new CountDownLatch(1);
        var request = new AtomicReference<ProbeRequest>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) -> {
                request.set(input);
                submitted.countDown();
                return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
            });
            field(panel, "serverProfile", JComboBox.class).setSelectedItem(
                    TestServerConfigurationProvider.profileNamed("HIP2P · XQ FFFF/HHHH/GGGG"));
            field(panel, "did", JTextField.class).setText("FFFF-158409-EBDAC");
            field(panel, "check", JButton.class).doClick();
        });
        assertTrue(submitted.await(2, TimeUnit.SECONDS));
        assertEquals(TransportProtocol.XQ, request.get().protocol());
    }

    @Test
    void customEndpointsCanExplicitlyChooseXqTransport() throws Exception {
        var submitted = new CountDownLatch(1);
        var request = new AtomicReference<ProbeRequest>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) -> {
                request.set(input);
                submitted.countDown();
                return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
            });
            field(panel, "transport", JComboBox.class).setSelectedItem(TransportProtocol.XQ);
            field(panel, "servers", JTextArea.class).setText("127.0.0.1:45000");
            field(panel, "did", JTextField.class).setText("FFFF-158409-EBDAC");
            field(panel, "check", JButton.class).doClick();
        });
        assertTrue(submitted.await(2, TimeUnit.SECONDS));
        assertEquals(TransportProtocol.XQ, request.get().protocol());
    }

    @Test
    void transportSelectionCannotChangeWhileAProbeIsRunning() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) ->
                    new ProbeResult(ProbeResult.Status.INCONCLUSIVE));
            field(panel, "did", JTextField.class).setText("FFFF-158409-EBDAC");
            field(panel, "check", JButton.class).doClick();
            assertFalse(field(panel, "transport", JComboBox.class).isEnabled());
        });
    }

    @Test
    void theProtocolDescriptionIdentifiesXqWhenSelected() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) ->
                    new ProbeResult(ProbeResult.Status.INCONCLUSIVE));
            field(panel, "transport", JComboBox.class).setSelectedItem(TransportProtocol.XQ);
            assertEquals("XQ · IPv4 UDP · direct / LAN / UDP relay",
                    field(panel, "profile", JLabel.class).getText());
        });
    }

    @Test
    void anEncodedXqConfigurationSelectsTheMatchingTransport() throws Exception {
        var submitted = new CountDownLatch(1);
        var request = new AtomicReference<ProbeRequest>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) -> {
                request.set(input);
                submitted.countDown();
                return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
            });
            field(panel, "servers", JTextArea.class).setText(DeviceFixtures.XQ_FFFF_CONFIGURATION);
            field(panel, "did", JTextField.class).setText("FFFF-158409-EBDAC");
            field(panel, "check", JButton.class).doClick();
        });
        assertTrue(submitted.await(2, TimeUnit.SECONDS));
        assertEquals(TransportProtocol.XQ, request.get().protocol());
    }

    @Test
    void anSyEncodedConfigurationReplacesAPreviouslySelectedXqTransport() throws Exception {
        var submitted = new CountDownLatch(1);
        var request = new AtomicReference<ProbeRequest>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) -> {
                request.set(input);
                submitted.countDown();
                return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
            });
            field(panel, "serverProfile", JComboBox.class).setSelectedItem(
                    TestServerConfigurationProvider.profileNamed("HIP2P · XQ FFFF/HHHH/GGGG"));
            field(panel, "servers", JTextArea.class).setText(TestServerConfigurationProvider.SY_ENCODED);
            field(panel, "did", JTextField.class).setText("FFFF-158409-EBDAC");
            field(panel, "check", JButton.class).doClick();
            assertEquals(TransportProtocol.PPCS, field(panel, "transport", JComboBox.class).getSelectedItem());
        });
        assertTrue(submitted.await(2, TimeUnit.SECONDS));
        assertEquals(TransportProtocol.PPCS, request.get().protocol());
    }

    @Test
    void invalidInputDoesNotLeaveThePreviousResultOnScreen() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) -> new ProbeResult(ProbeResult.Status.INCONCLUSIVE));
            field(panel, "network", JLabel.class).setText("Server responded");
            field(panel, "peer", JLabel.class).setText("203.0.113.1:32108");
            field(panel, "deviceEndpoint", JLabel.class).setText("198.51.100.8:32108");
            field(panel, "p2pDetails", JTextArea.class).setText("Previous P2P information");
            field(panel, "did", JTextField.class).setText("not-a-did");
            field(panel, "check", JButton.class).doClick();
            assertEquals(List.of("Not checked", "—"), List.of(field(panel, "network", JLabel.class).getText(),
                    field(panel, "peer", JLabel.class).getText()));
            assertEquals("—", field(panel, "deviceEndpoint", JLabel.class).getText());
            assertEquals("", field(panel, "p2pDetails", JTextArea.class).getText());
        });
    }

    @Test
    void separatesEachRunInTheDiagnosticsLog() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) -> new ProbeResult(ProbeResult.Status.INCONCLUSIVE));
            field(panel, "did", JTextField.class).setText("FFFF-158409-EBDAC");
            field(panel, "check", JButton.class).doClick();
            assertTrue(field(panel, "log", JTextArea.class).getText().matches(
                    "── \\d\\d:\\d\\d:\\d\\d · FFFF-158409-EBDAC · PPCS ──\n"));
            field(panel, "clearLog", JButton.class).doClick();
            assertEquals("", field(panel, "log", JTextArea.class).getText());
        });
    }

    @Test
    void keepsTheDiagnosticsLogBounded() throws Exception {
        var done = new CountDownLatch(1);
        var view = new AtomicReference<AvailabilityPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) -> {
                for (int i = 0; i < 2500; i++) {
                    progress.accept("line " + i);
                }
                return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
            });
            view.set(panel);
            field(panel, "status", JLabel.class).addPropertyChangeListener("text", event -> {
                if ("Inconclusive".equals(event.getNewValue())) {
                    done.countDown();
                }
            });
            field(panel, "did", JTextField.class).setText("FFFF-158409-EBDAC");
            field(panel, "check", JButton.class).doClick();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            var text = field(view.get(), "log", JTextArea.class).getText();
            assertEquals(2000, text.lines().count());
            assertTrue(text.endsWith("line 2499\n"));
            assertEquals(IntStream.range(500, 2500).mapToObj(index -> "line " + index).toList(),
                    text.lines().map(line -> line.substring(10)).toList(), "Keep the exact ordered tail, not just its length");
        });
    }

    @Test
    void remembersTheSubmittedFormForTheNextSession() throws Exception {
        var saved = new AtomicReference<FormMemory.FormState>();
        var memory = new FormMemory() {
            @Override
            public Optional<FormState> load() {
                return Optional.ofNullable(saved.get());
            }

            @Override
            public void save(FormState state) {
                saved.set(state);
            }
        };
        SwingUtilities.invokeAndWait(() -> {
            var panel = new AvailabilityPanel((input, cancelled, progress) -> new ProbeResult(ProbeResult.Status.INCONCLUSIVE),
                    new TestServerConfigurationProvider(), memory);
            field(panel, "serverProfile", JComboBox.class).setSelectedIndex(1);
            field(panel, "did", JTextField.class).setText("WWWW-158409-EBDAC");
            field(panel, "timeout", JSpinner.class).setValue(25);
            field(panel, "check", JButton.class).doClick();
        });
        SwingUtilities.invokeAndWait(() -> {
            var restored = new AvailabilityPanel((input, cancelled, progress) -> new ProbeResult(ProbeResult.Status.INCONCLUSIVE),
                    new TestServerConfigurationProvider(), memory);
            assertEquals("WWWW-158409-EBDAC", field(restored, "did", JTextField.class).getText());
            assertEquals("HIP2P · SY WWWW/TTTT/UUUU",
                    field(restored, "serverProfile", JComboBox.class).getSelectedItem().toString());
            assertEquals("47.91.222.210:32100,47.93.34.139:32100,47.254.79.165:32100",
                    field(restored, "servers", JTextArea.class).getText());
            assertEquals(25, field(restored, "timeout", JSpinner.class).getValue());
        });
    }

    @Test
    void pressingEnterInTheFormRunsTheCheck() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) -> new ProbeResult(ProbeResult.Status.INCONCLUSIVE));
            var root = new JRootPane();
            root.getContentPane().add(panel);
            panel.addNotify();
            assertSame(field(panel, "check", JButton.class), root.getDefaultButton());
        });
    }

    @Test
    void worksWithoutAnyKnownServerProfiles() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new AvailabilityPanel((input, cancelled, progress) -> new ProbeResult(ProbeResult.Status.INCONCLUSIVE),
                    new TestServerConfigurationProvider(List.of()));
            assertFalse(field(panel, "serverProfile", JComboBox.class).isEnabled());
            assertEquals("", field(panel, "servers", JTextArea.class).getText());
        });
    }

    @Test
    void choosingAProfileKeepsEditedServersWhenTheUserDeclinesTheReplacement() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) -> new ProbeResult(ProbeResult.Status.INCONCLUSIVE));
            panel.confirmServerReplacement = chosen -> false;
            var profiles = field(panel, "serverProfile", JComboBox.class);
            field(panel, "servers", JTextArea.class).setText("192.0.2.10:32100");
            profiles.setSelectedIndex(2);
            assertEquals("192.0.2.10:32100", field(panel, "servers", JTextArea.class).getText());
            assertEquals(0, profiles.getSelectedIndex());
            assertEquals(TransportProtocol.PPCS, field(panel, "transport", JComboBox.class).getSelectedItem());
        });
    }

    @Test
    void choosingAProfileReplacesEditedServersOnceConfirmed() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) -> new ProbeResult(ProbeResult.Status.INCONCLUSIVE));
            var asked = new AtomicBoolean();
            panel.confirmServerReplacement = chosen -> {
                asked.set(true);
                return true;
            };
            field(panel, "serverProfile", JComboBox.class).setSelectedIndex(1);
            assertFalse(asked.get(), "An unedited list is replaced without asking");
            field(panel, "servers", JTextArea.class).setText("192.0.2.10:32100");
            field(panel, "serverProfile", JComboBox.class).setSelectedIndex(2);
            assertTrue(asked.get());
            assertEquals("52.221.17.159:32100,123.56.143.156:32100,52.8.0.180:32100",
                    field(panel, "servers", JTextArea.class).getText());
            assertEquals(TransportProtocol.XQ, field(panel, "transport", JComboBox.class).getSelectedItem());
        });
    }

    @Test
    void explainsBatchFormatsAndRoutingBeforeOpeningAFileWithoutAddingTabStops() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) -> {
                throw new AssertionError("Help must not start a probe");
            });
            assertFalse(field(panel, "batchCard", Component.class).isVisible());
            var help = field(panel, "batchHelp", JTextArea.class);
            for (Component ancestor = help; ancestor != null; ancestor = ancestor.getParent()) {
                assertTrue(ancestor.isVisible());
            }
            assertFalse(help.isEditable());
            assertFalse(help.isFocusable());
            assertTrue(help.getText().contains("single-check servers are ignored"));
            assertTrue(help.getText().contains("Serial,Description"));
            assertTrue(help.getText().contains("GWELL:01234"));
            assertTrue(help.getText().contains("IOTVIDEO:device-key"));
            var open = panel.menuBar().getMenu(0).getItem(0);
            assertEquals(field(panel, "batchCheck", JButton.class).getText(), open.getText());
            assertTrue(open.getToolTipText().contains("immediately"));
            assertTrue(field(panel, "accountHelp", JTextArea.class).getText().contains("does not validate"));
        });
    }

    @Test
    void selectingAProtocolUpdatesItsHelpWithoutRewritingInputsOrDefaults() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) -> {
                throw new AssertionError("Selecting a protocol must not start a probe");
            });
            field(panel, "did", JTextField.class).setText("Keep_001234");
            var servers = field(panel, "servers", JTextArea.class).getText();
            var selectedProfile = field(panel, "serverProfile", JComboBox.class).getSelectedItem();
            for (var protocol : TransportProtocol.values()) {
                field(panel, "transport", JComboBox.class).setSelectedItem(protocol);
                var did = field(panel, "did", JTextField.class);
                var help = field(panel, "identifierHelp", JTextArea.class);
                assertEquals(AvailabilityPresenter.identifierHelp(protocol), help.getText());
                assertEquals(help.getText(), did.getToolTipText());
                assertEquals(help.getText(), did.getAccessibleContext().getAccessibleDescription());
                assertFalse(help.isFocusable());
                assertEquals("Keep_001234", did.getText());
                assertEquals(servers, field(panel, "servers", JTextArea.class).getText());
                assertSame(selectedProfile, field(panel, "serverProfile", JComboBox.class).getSelectedItem());
                assertEquals(10, field(panel, "timeout", JSpinner.class).getValue());
                assertTrue(field(panel, "lanSearch", JCheckBox.class).isSelected());
            }
        });
    }

    @Test
    void theInitialResultOwnerIsReadableAndNotDerivedFromUnsubmittedFormEdits() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = newPanel((input, cancelled, progress) -> {
                throw new AssertionError("Editing the form must not start a probe");
            });
            var context = field(panel, "resultContext", JTextArea.class);
            assertEquals("No check submitted", context.getText());
            assertFalse(context.isEditable());
            assertTrue(context.getLineWrap());
            assertTrue(context.getFont().isBold());
            assertEquals("Displayed result owner", context.getAccessibleContext().getAccessibleName());
            field(panel, "did", JTextField.class).setText("Ab_01234");
            field(panel, "transport", JComboBox.class).setSelectedItem(TransportProtocol.IOTVIDEO);
            assertEquals("No check submitted", context.getText());
        });
    }

    private static AvailabilityPanel newPanel(ProbeRunner runner) {
        return new AvailabilityPanel(runner, new TestServerConfigurationProvider());
    }

    static <T extends Component> T field(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) {
                return type.cast(child);
            }
            if (child instanceof Container container) {
                T found = search(container, name, type);
                if (found != null) {
                    return found;
                }
            }
        }
        throw new AssertionError("Missing UI component: " + name);
    }

    private static <T extends Component> T search(Container root, String name, Class<T> type) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) {
                return type.cast(child);
            }
            if (child instanceof Container container) {
                T found = search(container, name, type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
