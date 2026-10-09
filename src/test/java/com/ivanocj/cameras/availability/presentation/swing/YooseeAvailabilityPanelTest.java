package com.ivanocj.cameras.availability.presentation.swing;

import com.ivanocj.cameras.availability.application.model.GwellLanDevice;
import com.ivanocj.cameras.availability.application.model.P2pInformation;
import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.domain.model.GwellDeviceId;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.infrastructure.configuration.DefaultServerConfigurationProvider;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class YooseeAvailabilityPanelTest {
    @Test void credentialSelectionIsEphemeralAndLockedUntilWorkerCleanupCompletes() throws Exception {
        var selected = new AtomicReference<Optional<Path>>(Optional.empty());
        var changes = new AtomicInteger();
        var saved = new AtomicReference<FormMemory.FormState>();
        var release = new CountDownLatch(1);
        var rendered = new CountDownLatch(1);
        var view = new AtomicReference<AvailabilityPanel>();
        var privatePath = Path.of("PRIVATE_NAME.credentials.properties");
        var memory = new FormMemory() {
            @Override public Optional<FormState> load() { return Optional.empty(); }
            @Override public void save(FormState state) { saved.set(state); }
        };
        try {
            SwingUtilities.invokeAndWait(() -> {
                var panel = new AvailabilityPanel((request, cancel, progress) -> {
                    try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { throw new java.io.InterruptedIOException(); }
                    return new ProbeResult(ProbeResult.Status.AUTH_REQUIRED);
                }, new DefaultServerConfigurationProvider(), memory, path -> {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    selected.set(path); changes.incrementAndGet();
                });
                view.set(panel);
                panel.chooseCredentialsFile = () -> Optional.of(privatePath);
                var choose = AvailabilityPanelTest.field(panel, "credentialsFile", JButton.class);
                var clear = AvailabilityPanelTest.field(panel, "clearCredentials", JButton.class);
                choose.doClick();
                assertEquals(Optional.of(privatePath), selected.get());
                assertFalse(choose.getText().contains("PRIVATE_NAME"));
                panel.chooseCredentialsFile = Optional::empty;
                choose.doClick();
                assertEquals(1, changes.get(), "Cancelling the chooser must keep the selected account");
                clear.doClick();
                assertTrue(selected.get().isEmpty());
                panel.chooseCredentialsFile = () -> Optional.of(privatePath);
                choose.doClick();
                AvailabilityPanelTest.field(panel, "transport", JComboBox.class).setSelectedItem(TransportProtocol.GWELL);
                AvailabilityPanelTest.field(panel, "servers", JTextArea.class).setText("127.0.0.1:51701");
                AvailabilityPanelTest.field(panel, "did", JTextField.class).setText("01234");
                AvailabilityPanelTest.field(panel, "status", JLabel.class).addPropertyChangeListener("text", event -> {
                    if ("Account credentials required".equals(event.getNewValue())) { rendered.countDown(); }
                });
                AvailabilityPanelTest.field(panel, "check", JButton.class).doClick();
                assertFalse(choose.isEnabled());
                assertFalse(clear.isEnabled());
                clear.doClick();
                assertEquals(3, changes.get());
                assertFalse(saved.get().toString().contains("PRIVATE_NAME"));
            });
        } finally { release.countDown(); }
        assertTrue(rendered.await(2, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(AvailabilityPanelTest.field(view.get(), "credentialsFile", JButton.class).isEnabled());
            var clear = AvailabilityPanelTest.field(view.get(), "clearCredentials", JButton.class);
            assertTrue(clear.isEnabled());
            clear.doClick();
            assertTrue(selected.get().isEmpty());
            assertEquals(4, changes.get());
            view.get().close();
        });
    }

    @Test
    void selectingTheGwellProfileSubmitsNumericIdentityAndShowsDiscoveryOnTheEdt() throws Exception {
        var submitted = new AtomicReference<ProbeRequest>();
        var rendered = new CountDownLatch(1);
        var view = new AtomicReference<AvailabilityPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = new AvailabilityPanel((request, cancel, progress) -> {
                assertFalse(SwingUtilities.isEventDispatchThread());
                submitted.set(request);
                var endpoint = new InetSocketAddress("192.168.1.8", 8899);
                var device = new GwellLanDevice((GwellDeviceId) request.identifier(), endpoint, 7, 3, 1 << 6,
                        Optional.empty(), OptionalInt.empty());
                return new ProbeResult(ProbeResult.Status.DISCOVERED_LAN, "Discovery only.", false,
                        Optional.empty(), Optional.empty(), Duration.ofSeconds(1),
                        new P2pInformation(Optional.of(TransportProtocol.GWELL), P2pInformation.ConnectionMode.NONE,
                                Optional.empty(), List.of(), List.of(), List.of(endpoint), List.of(), Map.of()), List.of(device));
            }, new DefaultServerConfigurationProvider());
            view.set(panel);
            var profiles = AvailabilityPanelTest.field(panel, "serverProfile", JComboBox.class);
            for (int index = 0; index < profiles.getItemCount(); index++) {
                if (profiles.getItemAt(index).toString().equals("Yoosee · GWell LAN discovery")) profiles.setSelectedIndex(index);
            }
            assertEquals("", AvailabilityPanelTest.field(panel, "servers", JTextArea.class).getText());
            assertEquals(TransportProtocol.GWELL, AvailabilityPanelTest.field(panel, "transport", JComboBox.class).getSelectedItem());
            AvailabilityPanelTest.field(panel, "status", JLabel.class).addPropertyChangeListener("text", event -> {
                if ("Discovered on LAN".equals(event.getNewValue())) {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    rendered.countDown();
                }
            });
            AvailabilityPanelTest.field(panel, "did", JTextField.class).setText("01234");
            AvailabilityPanelTest.field(panel, "check", JButton.class).doClick();
        });
        assertTrue(rendered.await(2, TimeUnit.SECONDS));
        assertEquals(new GwellDeviceId("01234"), submitted.get().identifier());
        assertTrue(submitted.get().servers().isEmpty());
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("Not checked · LAN only", AvailabilityPanelTest.field(view.get(), "network", JLabel.class).getText());
            assertEquals("—", AvailabilityPanelTest.field(view.get(), "peer", JLabel.class).getText());
            assertTrue(AvailabilityPanelTest.field(view.get(), "p2pDetails", JTextArea.class).getText().contains("type=7"));
            assertTrue(AvailabilityPanelTest.field(view.get(), "check", JButton.class).isEnabled());
        });
    }
}
