package com.ivanocj.cameras.availability.presentation.swing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class CollapsibleSectionTest {
    @Test
    void disclosureOnlyChangesVisibilityAndKeepsTheControlsAndTheirState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var input = new JTextField("001234");
            input.setEnabled(false);
            var content = new JPanel();
            content.add(input);
            var section = new CollapsibleSection("settings", "Settings", content);
            var toggle = (JToggleButton) section.getComponent(0);
            assertFalse(content.isVisible());
            toggle.doClick(0);
            assertTrue(content.isVisible());
            assertSame(content, input.getParent());
            assertFalse(input.isEnabled());
            assertEquals("001234", input.getText());
            assertEquals("Expanded", toggle.getAccessibleContext().getAccessibleDescription());
            toggle.doClick(0);
            assertFalse(content.isVisible());
            assertSame(content, input.getParent());
            assertEquals("001234", input.getText());
            assertEquals("Collapsed", toggle.getAccessibleContext().getAccessibleDescription());
        });
    }

    @Test
    void revealingAnInvalidFieldIsIdempotentAndKeepsDisclosureStateAccessible() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var content = new JPanel();
            var section = new CollapsibleSection("settings", "Settings", content);
            var toggle = (JToggleButton) section.getComponent(0);
            section.expand();
            section.expand();
            assertTrue(toggle.isSelected());
            assertTrue(content.isVisible());
            assertEquals("Expanded", toggle.getAccessibleContext().getAccessibleDescription());
            toggle.doClick(0);
            assertFalse(content.isVisible());
        });
    }
}
