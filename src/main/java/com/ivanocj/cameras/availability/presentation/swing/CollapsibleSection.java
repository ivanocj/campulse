package com.ivanocj.cameras.availability.presentation.swing;

import java.awt.BorderLayout;
import java.awt.KeyboardFocusManager;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

/** Visibility-only disclosure: keeps the same controls, values, enabled states, and listeners. */
final class CollapsibleSection extends JPanel {
    private final JToggleButton toggle;
    private final JComponent content;
    private final String title;

    CollapsibleSection(String name, String title, JComponent content) {
        super(new BorderLayout(0, 8));
        this.title = title;
        this.content = content;
        setName(name);
        setOpaque(false);
        content.setName(name + "Content");
        toggle = new JToggleButton();
        toggle.setName(name + "Toggle");
        toggle.setHorizontalAlignment(JToggleButton.LEADING);
        toggle.getAccessibleContext().setAccessibleName(title);
        add(toggle, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
        toggle.addActionListener(event -> update());
        update();
    }

    /** Reveals an invalid field without toggling an already-expanded section closed. */
    void expand() {
        toggle.setSelected(true);
        update();
    }

    private void update() {
        boolean expanded = toggle.isSelected();
        var focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (!expanded && focused != null && SwingUtilities.isDescendingFrom(focused, content)) {
            toggle.requestFocusInWindow();
        }
        content.setVisible(expanded);
        toggle.setText((expanded ? "▾ " : "▸ ") + title);
        toggle.getAccessibleContext().setAccessibleDescription(expanded ? "Expanded" : "Collapsed");
        revalidate();
        repaint();
    }
}
