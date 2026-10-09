package com.ivanocj.cameras.availability.presentation.swing;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** EDT-only field decoration; displays an existing error, never validates or submits input. */
final class FieldFeedback extends JPanel {
    private final JComponent input;
    private final JComponent outlinedView;
    private final JTextArea message = new JTextArea(0, 24) {
        @Override public void setBounds(int x, int y, int width, int height) {
            boolean widthChanged = width != getWidth();
            super.setBounds(x, y, width, height);
            if (widthChanged) { scheduleHeightUpdate(); }
        }
    };
    private final JPanel messageRow;
    private Object originalOutline;
    private Object originalViewOutline;
    private String originalDescription;
    private boolean heightUpdatePending;

    FieldFeedback(JComponent input, Component view) {
        this.input = input;
        outlinedView = view instanceof JScrollPane scroll ? scroll : input;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setName(input.getName() + "Feedback");
        setOpaque(false);
        message.setName(input.getName() + "Error");
        message.setEditable(false);
        message.setFocusable(false);
        message.setOpaque(false);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        message.setFont(UIManager.getFont("Label.font"));
        message.setForeground(AvailabilityTheme.NEGATIVE);
        message.getAccessibleContext().setAccessibleName("Validation message");
        message.setVisible(false);
        add(row(view));
        messageRow = row(message);
        messageRow.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        messageRow.setVisible(false);
        add(messageRow);
    }

    private static JPanel row(Component content) {
        var row = new JPanel(new BorderLayout()) {
            @Override public Dimension getMinimumSize() {
                return new Dimension(0, getPreferredSize().height);
            }
            @Override public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(content, BorderLayout.CENTER);
        return row;
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
        boolean widthChanged = width != getWidth();
        super.setBounds(x, y, width, height);
        if (widthChanged) {
            scheduleHeightUpdate();
        }
    }

    private void scheduleHeightUpdate() {
        if (heightUpdatePending) { return; }
        heightUpdatePending = true;
        // Never invalidate an ancestor while its BoxLayout is calculating child size requests.
        SwingUtilities.invokeLater(() -> {
            heightUpdatePending = false;
            invalidateHeights();
        });
    }

    private void invalidateHeights() {
        // Wrapping changes preferred heights after width is assigned. Explicitly discard ancestor
        // BoxLayout caches too, including offscreen previews whose containers are never validated.
        for (Container current = this; current != null; current = current.getParent()) { current.invalidate(); }
        revalidate();
    }

    void showError(String error) {
        if (!message.isVisible()) {
            originalOutline = input.getClientProperty("JComponent.outline");
            originalViewOutline = outlinedView.getClientProperty("JComponent.outline");
            originalDescription = input.getAccessibleContext().getAccessibleDescription();
        }
        message.setText("Error: " + error);
        message.setVisible(true);
        messageRow.setVisible(true);
        input.putClientProperty("JComponent.outline", "error");
        outlinedView.putClientProperty("JComponent.outline", "error");
        input.getAccessibleContext().setAccessibleDescription(
                (originalDescription == null || originalDescription.isBlank() ? "" : originalDescription + "\n") + message.getText());
        input.repaint();
        outlinedView.repaint();
        invalidateHeights();
        repaint();
    }

    void clear() {
        if (!message.isVisible()) { return; }
        input.putClientProperty("JComponent.outline", originalOutline);
        outlinedView.putClientProperty("JComponent.outline", originalViewOutline);
        input.getAccessibleContext().setAccessibleDescription(originalDescription);
        message.setText("");
        message.setVisible(false);
        messageRow.setVisible(false);
        input.repaint();
        outlinedView.repaint();
        invalidateHeights();
        repaint();
    }

    void focusInput() {
        SwingUtilities.invokeLater(() -> {
            // Do not steal focus if the user already corrected/collapsed the field or started other work.
            if (!message.isVisible() || !input.isEnabled()) { return; }
            for (Component current = input; current != null; current = current.getParent()) {
                if (!current.isVisible()) { return; }
            }
            scrollRectToVisible(new Rectangle(0, 0, getWidth(), getHeight()));
            input.requestFocusInWindow();
        });
    }
}
