package com.ivanocj.cameras.availability.presentation.swing;

import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.port.ProbeRunner;
import com.ivanocj.cameras.availability.testsupport.TestServerConfigurationProvider;

import static com.ivanocj.cameras.availability.presentation.swing.AvailabilityPanelTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Container;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JRootPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class KeyboardNavigationTest {
    @Test
    void configurationLabelsAndExistingCommandsHaveDistinctVisibleMnemonics() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel(unusedRunner());
            var used = new HashSet<Integer>();
            var fields = Map.of("did", KeyEvent.VK_D, "serverProfile", KeyEvent.VK_P, "servers", KeyEvent.VK_S,
                     "timeout", KeyEvent.VK_T, "transport", KeyEvent.VK_R, "batchParallelism", KeyEvent.VK_K,
                    "uidPrefix", KeyEvent.VK_H, "uidSerial", KeyEvent.VK_I,
                    "batchSearch", KeyEvent.VK_Q, "batchOutcomeFilter", KeyEvent.VK_M);
            for (var entry : fields.entrySet()) {
                var label = field(panel, entry.getKey() + "Label", JLabel.class);
                assertEquals(entry.getValue().intValue(), label.getDisplayedMnemonic());
                assertTrue(label.getDisplayedMnemonicIndex() >= 0);
                assertSame(field(panel, entry.getKey(), JComponent.class), label.getLabelFor());
                assertTrue(used.add(label.getDisplayedMnemonic()), "Mnemonic collision: " + entry.getKey());
            }
            var commands = Map.of("lanSearch", KeyEvent.VK_N, "credentialsFile", KeyEvent.VK_E,
                     "clearCredentials", KeyEvent.VK_U, "check", KeyEvent.VK_C, "batchCheck", KeyEvent.VK_O,
                    "cancel", KeyEvent.VK_A, "uidGenerate", KeyEvent.VK_B, "clearLog", KeyEvent.VK_L,
                    "clearBatchFilters", KeyEvent.VK_W);
            for (var entry : commands.entrySet()) {
                var button = field(panel, entry.getKey(), AbstractButton.class);
                assertEquals(entry.getValue().intValue(), button.getMnemonic());
                assertTrue(button.getDisplayedMnemonicIndex() >= 0);
                assertTrue(used.add(button.getMnemonic()), "Mnemonic collision: " + entry.getKey());
            }
            assertTrue(used.add(panel.menuBar().getMenu(0).getMnemonic()));
            assertEquals(KeyEvent.VK_F, panel.menuBar().getMenu(0).getMnemonic());
            panel.chooseCredentialsFile = () -> Optional.of(Path.of("PRIVATE_NAME.credentials.properties"));
            var selectAccount = field(panel, "credentialsFile", JButton.class);
            selectAccount.doClick(0);
            assertEquals(KeyEvent.VK_E, selectAccount.getMnemonic());
            assertTrue(selectAccount.getDisplayedMnemonicIndex() >= 0, "Keep the mnemonic visible when the caption changes");
            assertFalse(selectAccount.getText().contains("PRIVATE_NAME"));
        });
    }

    @Test
    void cancelShortcutSharesTheButtonActionAndIsInactiveBeforeAnyOperation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel(unusedRunner());
            var key = KeyStroke.getKeyStroke(KeyEvent.VK_PERIOD, InputEvent.CTRL_DOWN_MASK);
            var binding = panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(key);
            assertNotNull(binding);
            var action = panel.getActionMap().get(binding);
            assertSame(field(panel, "cancel", JButton.class).getAction(), action);
            assertEquals(key, action.getValue(Action.ACCELERATOR_KEY));
            assertFalse(action.isEnabled());
            action.actionPerformed(new ActionEvent(panel, ActionEvent.ACTION_PERFORMED, "cancel"));
            assertEquals("Ready to check", field(panel, "status", JLabel.class).getText());
            assertTrue(field(panel, "check", JButton.class).isEnabled());
        });
    }

    @Test
    void theExistingRootEnterBindingStillSubmitsTheUsersIdentifier() throws Exception {
        var called = new CountDownLatch(1);
        var request = new AtomicReference<ProbeRequest>();
        var view = new AtomicReference<AvailabilityPanel>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var panel = panel((input, cancelled, progress) -> {
                    request.set(input);
                    called.countDown();
                    return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
                });
                view.set(panel);
                var root = attach(panel);
                field(panel, "did", JTextField.class).setText(" ffff-158409-ebdac ");
                var key = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0);
                var binding = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(key);
                assertNotNull(binding);
                var action = root.getActionMap().get(binding);
                assertNotNull(action);
                action.actionPerformed(new ActionEvent(root, ActionEvent.ACTION_PERFORMED, binding.toString()));
            });
            assertTrue(called.await(5, TimeUnit.SECONDS));
            assertEquals("FFFF-158409-EBDAC", request.get().identifier().value());
        } finally {
            if (view.get() != null) { SwingUtilities.invokeAndWait(() -> view.get().close()); }
        }
    }

    @Test
    void enterInTheHichipSerialBuildsTheDidWithoutStartingTheDefaultCheck() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel(unusedRunner());
            var root = attach(panel);
            field(panel, "uidPrefix", JTextField.class).setText("ffff");
            var serial = field(panel, "uidSerial", JTextField.class);
            serial.setText("158409");
            serial.postActionEvent();
            assertEquals("FFFF-158409-EBDAC", field(panel, "did", JTextField.class).getText());
            assertEquals("Ready to check", field(panel, "status", JLabel.class).getText());
            assertSame(field(panel, "check", JButton.class), root.getDefaultButton());
        });
    }

    @Test
    void applicationShortcutsLeaveEscapeTextEditingAndTableNavigationToSwing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel(unusedRunner());
            var escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
            assertNull(panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(escape));
            assertNull(new JRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_PERIOD, InputEvent.CTRL_DOWN_MASK)));
            var servers = field(panel, "servers", JTextArea.class);
            var referenceText = new JTextArea();
            for (var key : new KeyStroke[] {KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
                    KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK),
                    KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), KeyStroke.getKeyStroke('.'),
                    KeyStroke.getKeyStroke(KeyEvent.VK_TAB, InputEvent.CTRL_DOWN_MASK)}) {
                assertEquals(referenceText.getInputMap(JComponent.WHEN_FOCUSED).get(key),
                        servers.getInputMap(JComponent.WHEN_FOCUSED).get(key));
            }
            var referenceCombo = new JComboBox<>();
            assertEquals(referenceCombo.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(escape),
                    field(panel, "transport", JComboBox.class).getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(escape));
            var referenceTable = new JTable();
            var down = KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0);
            assertEquals(referenceTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(down),
                    field(panel, "batchResults", JTable.class).getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(down));
        });
    }

    @Test
    void batchMenuAndButtonKeepTheirSharedPlatformOpenShortcut() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel(unusedRunner());
            var open = panel.menuBar().getMenu(0).getItem(0);
            assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK), open.getAccelerator());
            assertSame(field(panel, "batchCheck", JButton.class).getAction(), open.getAction());
        });
    }

    @Test
    void configurationHierarchyKeepsVisualOrderAndSkipsHelpAndDisabledCancel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = panel(unusedRunner());
            var expected = List.of("did", "serverProfile", "check", "batchCheck", "batchParallelism");
            var all = List.of("did", "serverProfile", "check", "servers", "timeout", "transport", "lanSearch",
                    "batchCheck", "batchParallelism", "credentialsFile", "clearCredentials", "uidPrefix", "uidSerial", "uidGenerate");
            var names = new HashSet<>(all);
            names.add("cancel");
            var actual = new ArrayList<String>();
            configurationNames(panel, names, actual);
            assertEquals(expected, actual);
            for (var name : List.of("connectionSettings", "accountSettings", "evidence", "hichipTools", "diagnostics")) {
                field(panel, name + "Toggle", JToggleButton.class).doClick(0);
            }
            actual.clear();
            configurationNames(panel, names, actual);
            assertEquals(all, actual);
            for (var name : List.of("identifierHelp", "accountHelp", "batchHelp")) {
                assertFalse(field(panel, name, JTextArea.class).isFocusable());
            }
        });
    }

    private static void configurationNames(Container root, Set<String> names, List<String> found) {
        for (var child : root.getComponents()) {
            if (!child.isVisible()) { continue; }
            if (names.contains(child.getName()) && child.isVisible() && child.isEnabled()) { found.add(child.getName()); }
            if (child instanceof Container nested) { configurationNames(nested, names, found); }
        }
    }

    private static JRootPane attach(AvailabilityPanel panel) {
        var root = new JRootPane();
        root.getContentPane().add(panel);
        panel.addNotify();
        return root;
    }

    private static AvailabilityPanel panel(ProbeRunner runner) {
        return new AvailabilityPanel(runner, new TestServerConfigurationProvider());
    }

    private static ProbeRunner unusedRunner() {
        return (request, cancelled, progress) -> { throw new AssertionError("No check was requested"); };
    }
}
