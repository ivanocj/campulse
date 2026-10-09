package com.ivanocj.cameras.availability.presentation.swing;

import java.awt.Container;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/** Keeps keyboard focus visible in the outer form, including text fields with their own scroll behavior. */
final class KeyboardScrollSupport extends FocusAdapter {
    private final JComponent owner;

    KeyboardScrollSupport(JComponent owner) {
        this.owner = owner;
    }

    void attach(Container container) {
        for (var child : container.getComponents()) {
            if (child instanceof JComponent component && component.isFocusable()) { component.addFocusListener(this); }
            if (child instanceof Container nested) { attach(nested); }
        }
    }

    @Override
    public void focusGained(FocusEvent event) {
        var component = (JComponent) event.getComponent();
        var nestedScroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, component);
        JComponent target = nestedScroll != null && SwingUtilities.isDescendingFrom(nestedScroll, owner) ? nestedScroll : component;
        var bounds = SwingUtilities.convertRectangle(target.getParent(), target.getBounds(), owner);
        owner.scrollRectToVisible(bounds);
    }
}
