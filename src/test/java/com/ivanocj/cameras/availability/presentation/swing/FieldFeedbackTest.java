package com.ivanocj.cameras.availability.presentation.swing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class FieldFeedbackTest {
    @Test
    void errorsAreTextualAndRestoreTheOriginalOutlineAndAccessibleDescription() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var input = new JTextField();
            input.setName("example");
            input.putClientProperty("JComponent.outline", "warning");
            input.getAccessibleContext().setAccessibleDescription("Original help");
            var feedback = new FieldFeedback(input, input);
            var message = AvailabilityPanelTest.field(feedback, "exampleError", JTextArea.class);
            assertFalse(message.isVisible());
            feedback.showError("First failure.");
            feedback.showError("Updated failure.");
            assertEquals("error", input.getClientProperty("JComponent.outline"));
            assertEquals("Original help\nError: Updated failure.", input.getAccessibleContext().getAccessibleDescription());
            assertEquals("Error: Updated failure.", message.getText());
            assertFalse(message.isFocusable());
            assertFalse(message.isEditable());
            feedback.clear();
            assertEquals("warning", input.getClientProperty("JComponent.outline"));
            assertEquals("Original help", input.getAccessibleContext().getAccessibleDescription());
            assertFalse(message.isVisible());
            assertEquals("", message.getText());
            feedback.clear();
            assertEquals("warning", input.getClientProperty("JComponent.outline"));
        });
    }

    @Test
    void aFieldWithoutPriorHelpCanRestoreItsUnannotatedState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var input = new JTextField();
            input.setName("example");
            var feedback = new FieldFeedback(input, input);
            feedback.showError("Fix this field.");
            assertEquals("Error: Fix this field.", input.getAccessibleContext().getAccessibleDescription());
            feedback.clear();
            assertNull(input.getAccessibleContext().getAccessibleDescription());
            assertNull(input.getClientProperty("JComponent.outline"));
        });
    }

    @Test
    void textAreaErrorsAlsoDecorateAndRestoreTheVisibleScrollPaneBorder() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var input = new JTextArea();
            input.setName("example");
            var scroll = new JScrollPane(input);
            scroll.putClientProperty("JComponent.outline", "warning");
            var feedback = new FieldFeedback(input, scroll);
            feedback.showError("Fix the server list.");
            assertEquals("error", input.getClientProperty("JComponent.outline"));
            assertEquals("error", scroll.getClientProperty("JComponent.outline"));
            feedback.clear();
            assertNull(input.getClientProperty("JComponent.outline"));
            assertEquals("warning", scroll.getClientProperty("JComponent.outline"));
        });
    }

    @Test
    void deferredFocusTargetsTheMarkedInputButNotAnAlreadyClearedOrDisabledField() throws Exception {
        var requests = new AtomicInteger();
        var feedback = new AtomicReference<FieldFeedback>();
        var input = new AtomicReference<JTextField>();
        SwingUtilities.invokeAndWait(() -> {
            var field = new JTextField() {
                @Override public boolean requestFocusInWindow() {
                    assertTrue(SwingUtilities.isEventDispatchThread());
                    requests.incrementAndGet();
                    return true;
                }
            };
            field.setName("example");
            input.set(field);
            feedback.set(new FieldFeedback(field, field));
            feedback.get().showError("Fix this field.");
            feedback.get().focusInput();
            assertEquals(0, requests.get(), "Layout must have a chance to reveal the field first");
        });
        SwingUtilities.invokeAndWait(() -> assertEquals(1, requests.get()));
        SwingUtilities.invokeAndWait(() -> {
            feedback.get().focusInput();
            feedback.get().clear();
        });
        SwingUtilities.invokeAndWait(() -> assertEquals(1, requests.get()));
        SwingUtilities.invokeAndWait(() -> {
            feedback.get().showError("Fix this field.");
            feedback.get().focusInput();
            input.get().setEnabled(false);
        });
        SwingUtilities.invokeAndWait(() -> assertEquals(1, requests.get()));
    }
}
