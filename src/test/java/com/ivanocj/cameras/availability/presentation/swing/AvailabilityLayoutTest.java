package com.ivanocj.cameras.availability.presentation.swing;

import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.testsupport.TestServerConfigurationProvider;

import static com.ivanocj.cameras.availability.presentation.swing.AvailabilityPanelTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.FocusEvent;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class AvailabilityLayoutTest {
    @Test
    void secondarySectionsStartCollapsedAndAccountAccessNeverDependsOnSingleProtocol() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel();
            for (var name : List.of("connectionSettings", "hichipTools", "evidence", "diagnostics", "accountSettings")) {
                var toggle = field(panel, name + "Toggle", JToggleButton.class);
                assertFalse(toggle.isSelected());
                assertTrue(visible(toggle));
            }
            for (var name : List.of("servers", "timeout", "transport", "uidPrefix", "uidSerial", "p2pDetails", "log", "credentialsFile")) {
                assertFalse(visible(field(panel, name, JComponent.class)), name);
            }
            assertTrue(visible(field(panel, "did", JTextField.class)));
            assertTrue(visible(field(panel, "check", JButton.class)));
            assertTrue(visible(field(panel, "batchCheck", JButton.class)));
            assertTrue(SwingUtilities.isDescendingFrom(field(panel, "batchParallelism", JSpinner.class),
                    field(panel, "batchCheck", JButton.class).getParent()));
            var account = field(panel, "accountSettingsToggle", JToggleButton.class);
            for (var protocol : TransportProtocol.values()) {
                field(panel, "transport", JComboBox.class).setSelectedItem(protocol);
                assertTrue(visible(account));
                account.doClick(0);
                assertTrue(visible(field(panel, "credentialsFile", JButton.class)));
                account.doClick(0);
            }
        });
    }

    @Test
    void expandingAndResizingRetainsTheSameComponentsValuesAndDoesNotSubmitWork() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel();
            var servers = field(panel, "servers", JTextArea.class);
            var timeout = field(panel, "timeout", JSpinner.class);
            var transport = field(panel, "transport", JComboBox.class);
            var lan = field(panel, "lanSearch", JCheckBox.class);
            servers.setText("192.0.2.40:45000");
            timeout.setValue(19);
            transport.setSelectedItem(TransportProtocol.XQ);
            lan.setSelected(false);
            var components = List.of(servers, timeout, transport, lan);
            for (int width : new int[] {960, 680, 1100, 680}) {
                panel.setSize(width, 1200);
                layout(panel);
                for (var name : List.of("connectionSettings", "hichipTools", "evidence", "diagnostics", "accountSettings")) {
                    var toggle = field(panel, name + "Toggle", JToggleButton.class);
                    toggle.doClick(0);
                    toggle.doClick(0);
                }
            }
            assertEquals("192.0.2.40:45000", servers.getText());
            assertEquals(19, timeout.getValue());
            assertEquals(TransportProtocol.XQ, transport.getSelectedItem());
            assertFalse(lan.isSelected());
            assertEquals(components, List.of(field(panel, "servers", JTextArea.class), field(panel, "timeout", JSpinner.class),
                    field(panel, "transport", JComboBox.class), field(panel, "lanSearch", JCheckBox.class)));
            assertEquals("Ready to check", field(panel, "status", javax.swing.JLabel.class).getText());
        });
    }

    @Test
    void aCollapsedCustomizedConnectionSubmitsExactlyTheSameRequest() throws Exception {
        var submitted = new CountDownLatch(1);
        var request = new AtomicReference<ProbeRequest>();
        var view = new AtomicReference<AvailabilityPanel>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var panel = new AvailabilityPanel((input, cancelled, progress) -> {
                    request.set(input);
                    submitted.countDown();
                    return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
                }, new TestServerConfigurationProvider());
                view.set(panel);
                field(panel, "connectionSettingsToggle", JToggleButton.class).doClick(0);
                field(panel, "did", JTextField.class).setText("FFFF-000001-A");
                field(panel, "servers", JTextArea.class).setText("192.0.2.40:45000");
                field(panel, "transport", JComboBox.class).setSelectedItem(TransportProtocol.XQ);
                field(panel, "timeout", JSpinner.class).setValue(19);
                field(panel, "lanSearch", JCheckBox.class).doClick(0);
                field(panel, "connectionSettingsToggle", JToggleButton.class).doClick(0);
                var summary = field(panel, "connectionSummary", JTextArea.class).getText();
                assertTrue(summary.contains("XQ") && summary.contains("19") && summary.contains("192.0.2.40:45000"));
                assertTrue(summary.contains("off"));
                field(panel, "check", JButton.class).doClick(0);
            });
            assertTrue(submitted.await(5, TimeUnit.SECONDS));
            assertEquals("FFFF-000001-A", request.get().identifier().value());
            assertEquals(TransportProtocol.XQ, request.get().protocol());
            assertEquals("192.0.2.40", request.get().servers().getFirst().getHostString());
            assertEquals(45000, request.get().servers().getFirst().getPort());
            assertEquals(19, request.get().timeout().toSeconds());
            assertFalse(request.get().lanSearch());
        } finally {
            if (view.get() != null) { SwingUtilities.invokeAndWait(() -> view.get().close()); }
        }
    }

    @Test
    void theViewportTracksWidthAndTheCardsStackWithoutHorizontalClipping() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel();
            assertTrue((Object) panel instanceof Scrollable);
            var scrollable = (Scrollable) (Object) panel;
            assertTrue(scrollable.getScrollableTracksViewportWidth());
            var scroll = new JScrollPane(panel);
            for (int width : new int[] {720, 1100, 720}) {
                scroll.setSize(new Dimension(width, 560));
                layout(scroll);
                layout(scroll);
                var viewport = scroll.getViewport();
                assertEquals(viewport.getExtentSize().width, panel.getWidth());
                assertFalse(scroll.getHorizontalScrollBar().isVisible());
                var connection = field(panel, "connectionCard", JComponent.class);
                var result = field(panel, "resultCard", JComponent.class);
                var bounds = new Rectangle(0, 0, connection.getParent().getWidth(), connection.getParent().getHeight());
                assertTrue(bounds.contains(connection.getBounds()), width + " panel=" + panel.getSize() + " parent=" + bounds + " connection=" + connection.getBounds());
                assertTrue(bounds.contains(result.getBounds()), width + " parent=" + bounds + " result=" + result.getBounds());
                if (width == 720) {
                    assertTrue(result.getY() >= connection.getY() + connection.getHeight());
                } else {
                    assertEquals(connection.getY(), result.getY());
                    assertTrue(result.getX() >= connection.getX() + connection.getWidth());
                }
            }
        });
    }

    @Test
    void thePrimaryCheckAndResultHeadlineAreVisibleTogetherInASmallViewport() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel();
            var scroll = new JScrollPane(panel);
            scroll.setSize(720, 500);
            layout(scroll);
            layout(scroll);
            var viewport = scroll.getViewport().getViewRect();
            for (var name : List.of("did", "check", "status")) {
                var control = field(panel, name, JComponent.class);
                var bounds = SwingUtilities.convertRectangle(control.getParent(), control.getBounds(), panel);
                assertTrue(viewport.contains(bounds), name + " is outside the viewport: " + bounds + " / " + viewport);
            }
        });
    }

    @Test
    void expandedContentRecalculatesItsScrollableHeightWhenTheViewportNarrows() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AvailabilityTheme.install();
            var panel = panel();
            for (var name : List.of("connectionSettings", "accountSettings", "evidence", "hichipTools", "diagnostics")) {
                field(panel, name + "Toggle", JToggleButton.class).doClick(0);
            }
            var scroll = new JScrollPane(panel);
            for (int width : new int[] {960, 720}) {
                scroll.setSize(width, 560);
                layout(scroll);
                layout(scroll);
                assertTrue(panel.getHeight() >= panel.getPreferredSize().height,
                        "Scrollable height lost expanded rows: " + panel.getSize() + " / " + panel.getPreferredSize());
            }
        });
    }

    @Test
    void keyboardFocusScrollsAnExpandedTextFieldFullyIntoView() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel();
            for (var name : List.of("connectionSettings", "accountSettings", "evidence", "hichipTools", "diagnostics")) {
                field(panel, name + "Toggle", JToggleButton.class).doClick(0);
            }
            var scroll = new JScrollPane(panel);
            scroll.setSize(720, 560);
            layout(scroll);
            layout(scroll);
            var serial = field(panel, "uidSerial", JTextField.class);
            var bounds = SwingUtilities.convertRectangle(serial.getParent(), serial.getBounds(), panel);
            assertFalse(scroll.getViewport().getViewRect().contains(bounds));
            boolean installed = false;
            for (var listener : serial.getFocusListeners()) {
                if (listener instanceof KeyboardScrollSupport) {
                    listener.focusGained(new FocusEvent(serial, FocusEvent.FOCUS_GAINED));
                    installed = true;
                }
            }
            assertTrue(installed);
            assertTrue(scroll.getViewport().getViewRect().contains(bounds));
        });
    }

    private static AvailabilityPanel panel() {
        return new AvailabilityPanel((request, cancelled, progress) -> { throw new AssertionError("Layout must not run probes"); },
                new TestServerConfigurationProvider());
    }

    static boolean visible(Component component) {
        for (var current = component; current != null; current = current.getParent()) {
            if (!current.isVisible()) { return false; }
        }
        return true;
    }

    static void layout(Container root) {
        root.doLayout();
        for (var child : root.getComponents()) {
            if (child instanceof Container nested) { layout(nested); }
        }
    }
}
