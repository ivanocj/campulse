package com.ivanocj.cameras.availability.presentation.swing;

import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.infrastructure.configuration.DefaultServerConfigurationProvider;
import com.ivanocj.cameras.availability.testsupport.DeviceFixtures;

import static com.ivanocj.cameras.availability.presentation.swing.AvailabilityPanelTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FieldValidationPanelTest {
    @Test
    void identifierErrorsAreLocalAccessibleAndClearStaleEvidenceWithoutTesting() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel();
            field(panel, "did", JTextField.class).setText("broken");
            field(panel, "peer", JLabel.class).setText("192.0.2.4:32100");
            field(panel, "deviceEndpoint", JLabel.class).setText("192.0.2.5:32100");
            field(panel, "p2pDetails", JTextArea.class).setText("Stale evidence");
            field(panel, "check", JButton.class).doClick(0);
            assertError(panel, "did", "Enter a DID in PREFIX-SERIAL-CODE format.");
            assertFalse(field(panel, "serversError", JTextArea.class).isVisible());
            assertFalse(field(panel, "connectionSettingsToggle", JToggleButton.class).isSelected());
            assertEquals("Single check · input not submitted", field(panel, "resultContext", JTextArea.class).getText());
            assertEquals("—", field(panel, "peer", JLabel.class).getText());
            assertEquals("—", field(panel, "deviceEndpoint", JLabel.class).getText());
            assertEquals("", field(panel, "p2pDetails", JTextArea.class).getText());
            assertTrue(field(panel, "check", JButton.class).isEnabled());
        });
    }

    @Test
    void aServerFailureOpensItsSectionAndWinsWhenBothInputsAreInvalid() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel();
            field(panel, "did", JTextField.class).setText("broken");
            field(panel, "servers", JTextArea.class).setText("127.0.0.1:65536");
            field(panel, "check", JButton.class).doClick(0);
            assertTrue(field(panel, "connectionSettingsToggle", JToggleButton.class).isSelected());
            assertError(panel, "servers", "Server ports must be between 1 and 65535.");
            assertFalse(field(panel, "didError", JTextArea.class).isVisible());
            field(panel, "servers", JTextArea.class).setText("127.0.0.1:32100");
            assertClear(panel, "servers");
            assertEquals("Invalid input", field(panel, "status", JLabel.class).getText());
            field(panel, "check", JButton.class).doClick(0);
            assertError(panel, "did", "Enter a DID in PREFIX-SERIAL-CODE format.");
        });
    }

    @Test
    void editingAnIdentifierClearsOnlyItsMarkerAndRestoresNormalAccessibilityHelp() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel();
            var did = field(panel, "did", JTextField.class);
            String help = did.getAccessibleContext().getAccessibleDescription();
            did.setText("broken");
            field(panel, "check", JButton.class).doClick(0);
            assertError(panel, "did", "Enter a DID in PREFIX-SERIAL-CODE format.");
            did.setText("FFFF-1-A");
            assertClear(panel, "did");
            assertEquals(help, did.getAccessibleContext().getAccessibleDescription());
            assertEquals("Invalid input", field(panel, "status", JLabel.class).getText(), "Editing must not start work or invent a valid result");
        });
    }

    @Test
    void anEncodedOverrideUsesItsOwnIdentifierRuleAndConfigurationEditsClearStaleErrors() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel();
            field(panel, "did", JTextField.class).setText("ABCDEFG-4294967295-AB");
            field(panel, "servers", JTextArea.class).setText(DeviceFixtures.XQ_FFFF_CONFIGURATION);
            field(panel, "check", JButton.class).doClick(0);
            assertError(panel, "did", "XQ DIDs must fit the 20-byte ASCII identity field.");
            field(panel, "servers", JTextArea.class).setText("127.0.0.1");
            assertClear(panel, "did");
            field(panel, "did", JTextField.class).setText("broken");
            field(panel, "check", JButton.class).doClick(0);
            field(panel, "transport", JComboBox.class).setSelectedItem(TransportProtocol.GWELL);
            assertClear(panel, "did");
            assertTrue(field(panel, "did", JTextField.class).getAccessibleContext().getAccessibleDescription().contains("leading zero"));
        });
    }

    @Test
    void enablingLanClearsTheBlankServersErrorWithoutAutomaticallySubmitting() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel();
            field(panel, "did", JTextField.class).setText("FFFF-1-A");
            field(panel, "servers", JTextArea.class).setText("");
            field(panel, "lanSearch", JCheckBox.class).setSelected(false);
            field(panel, "check", JButton.class).doClick(0);
            assertError(panel, "servers", "Enter IPv4 server hosts, optionally with :port, separated by commas.");
            field(panel, "lanSearch", JCheckBox.class).setSelected(true);
            assertClear(panel, "servers");
            assertTrue(field(panel, "check", JButton.class).isEnabled());
            assertEquals("Invalid input", field(panel, "status", JLabel.class).getText());
        });
    }

    @Test
    void hiChipErrorsIdentifySerialOrPrefixAndEditsDoNotRunProbes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel();
            field(panel, "uidSerial", JTextField.class).setText("abc");
            field(panel, "uidGenerate", JButton.class).doClick(0);
            assertTrue(field(panel, "hichipToolsToggle", JToggleButton.class).isSelected());
            assertError(panel, "uidSerial", field(panel, "details", JTextArea.class).getText());
            assertClear(panel, "uidPrefix");
            field(panel, "uidSerial", JTextField.class).setText("158409");
            assertClear(panel, "uidSerial");
            field(panel, "uidPrefix", JTextField.class).setText("1");
            field(panel, "uidGenerate", JButton.class).doClick(0);
            assertError(panel, "uidPrefix", field(panel, "details", JTextArea.class).getText());
            assertClear(panel, "uidSerial");
            field(panel, "uidPrefix", JTextField.class).setText("FFFF");
            assertClear(panel, "uidPrefix");
            field(panel, "uidGenerate", JButton.class).doClick(0);
            assertEquals("FFFF-158409-EBDAC", field(panel, "did", JTextField.class).getText());
        });
    }

    @ParameterizedTest
    @EnumSource(value = TransportProtocol.class, names = {"GWELL", "IOTVIDEO"})
    void correctingFamilySpecificErrorsSubmitsTheUnchangedProtocolIdentity(TransportProtocol protocol) throws Exception {
        var submitted = new AtomicReference<ProbeRequest>();
        var view = new AtomicReference<AvailabilityPanel>();
        var completed = new CountDownLatch(1);
        String identifier = protocol == TransportProtocol.GWELL ? "001234" : "Ab_01234";
        SwingUtilities.invokeAndWait(() -> {
            var panel = new AvailabilityPanel((request, cancelled, progress) -> {
                submitted.set(request);
                return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
            }, new DefaultServerConfigurationProvider(), FormMemory.NONE);
            view.set(panel);
            field(panel, "transport", JComboBox.class).setSelectedItem(protocol);
            field(panel, "servers", JTextArea.class).setText("");
            field(panel, "check", JButton.class).doClick(0);
            assertError(panel, "did", field(panel, "details", JTextArea.class).getText());
            field(panel, "did", JTextField.class).setText(identifier);
            assertClear(panel, "did");
            field(panel, "status", JLabel.class).addPropertyChangeListener("text", event -> {
                if ("Inconclusive".equals(event.getNewValue())) { completed.countDown(); }
            });
            field(panel, "check", JButton.class).doClick(0);
        });
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(identifier, submitted.get().identifier().value());
            assertEquals(protocol, submitted.get().protocol());
            assertTrue(submitted.get().servers().isEmpty());
            assertClear(view.get(), "did");
            assertTrue(field(view.get(), "check", JButton.class).isEnabled());
        });
    }

    @Test
    void recoveryAdviceIsVisibleButDoesNotReplaceTheOriginalErrorOrSurviveANewInputError() throws Exception {
        var completed = new CountDownLatch(1);
        var view = new AtomicReference<AvailabilityPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = new AvailabilityPanel((request, cancelled, progress) -> {
                throw new IOException("Original local failure.", new IOException("PRIVATE_NESTED_DETAILS"));
            }, new DefaultServerConfigurationProvider(), FormMemory.NONE);
            view.set(panel);
            field(panel, "status", JLabel.class).addPropertyChangeListener("text", event -> {
                if ("Check failed".equals(event.getNewValue())) { completed.countDown(); }
            });
            field(panel, "did", JTextField.class).setText("FFFF-1-A");
            field(panel, "check", JButton.class).doClick(0);
        });
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            var panel = view.get();
            assertEquals("Original local failure.", field(panel, "details", JTextArea.class).getText());
            var recovery = field(panel, "recoveryHelp", JTextArea.class);
            assertTrue(AvailabilityLayoutTest.visible(recovery));
            assertTrue(recovery.getText().startsWith("Next step: "));
            assertTrue(recovery.getText().contains("network"));
            assertFalse(recovery.getText().contains("PRIVATE_NESTED_DETAILS"));
            assertFalse(recovery.isFocusable());
            field(panel, "did", JTextField.class).setText("broken");
            field(panel, "check", JButton.class).doClick(0);
            assertFalse(recovery.isVisible());
            assertEquals("", recovery.getText());
        });
    }

    @Test
    void aWrappedHiChipErrorDoesNotSqueezeItsInputOrLoseItsMessage() throws Exception {
        var view = new AtomicReference<AvailabilityPanel>();
        var viewport = new AtomicReference<JScrollPane>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel();
            view.set(panel);
            field(panel, "uidSerial", JTextField.class).setText("abc");
            field(panel, "uidGenerate", JButton.class).doClick(0);
            var scroll = new JScrollPane(panel);
            viewport.set(scroll);
            scroll.setSize(new Dimension(720, 560));
            AvailabilityLayoutTest.layout(scroll);
            AvailabilityLayoutTest.layout(scroll);
        });
        SwingUtilities.invokeAndWait(() -> {
            AvailabilityLayoutTest.layout(viewport.get());
            var input = field(view.get(), "uidSerial", JTextField.class);
            var message = field(view.get(), "uidSerialError", JTextArea.class);
            var feedback = field(view.get(), "uidSerialFeedback", JComponent.class);
            assertTrue(input.getHeight() >= input.getMinimumSize().height, "Validation must not squeeze the editable input");
            assertTrue(message.getHeight() >= message.getPreferredSize().height, "The complete wrapped error must be visible");
            assertTrue(new Rectangle(0, 0, feedback.getWidth(), feedback.getHeight()).contains(
                    SwingUtilities.convertRectangle(message.getParent(), message.getBounds(), feedback)), "The error must fit inside its field wrapper");
        });
    }

    private static AvailabilityPanel panel() {
        return new AvailabilityPanel((request, cancelled, progress) -> {
            throw new AssertionError("Rejected input, edits, and building a DID must not run a probe");
        }, new DefaultServerConfigurationProvider(), FormMemory.NONE);
    }

    private static void assertError(AvailabilityPanel panel, String name, String message) {
        var input = field(panel, name, JComponent.class);
        var error = field(panel, name + "Error", JTextArea.class);
        assertEquals("error", input.getClientProperty("JComponent.outline"));
        assertTrue(AvailabilityLayoutTest.visible(error));
        assertEquals("Error: " + message, error.getText());
        assertFalse(error.isEditable());
        assertFalse(error.isFocusable());
        assertTrue(input.getAccessibleContext().getAccessibleDescription().contains(message));
    }

    private static void assertClear(AvailabilityPanel panel, String name) {
        assertNull(field(panel, name, JComponent.class).getClientProperty("JComponent.outline"));
        assertFalse(field(panel, name + "Error", JTextArea.class).isVisible());
    }
}
