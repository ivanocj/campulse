package com.ivanocj.cameras.availability.bootstrap;

import com.ivanocj.cameras.availability.presentation.swing.AvailabilityPanel;
import com.ivanocj.cameras.availability.presentation.swing.AvailabilityPreviewFactory;
import com.ivanocj.cameras.availability.presentation.swing.AvailabilityTheme;
import java.awt.Container;
import java.awt.KeyboardFocusManager;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/** Test-only real-window validation check; no OS-wide input, real probes, or credential reads. */
public final class ValidationPreviewSmoke {
    private ValidationPreviewSmoke() {
    }

    public static void main(String[] args) throws Exception {
        boolean layoutOnly = args.length == 1 && args[0].equals("--layout-only");
        if (args.length > 0 && !layoutOnly) { throw new IllegalArgumentException("Use no arguments, or --layout-only."); }
        var window = new AtomicReference<JFrame>();
        var view = new AtomicReference<AvailabilityPanel>();
        var unexpected = new AtomicReference<Throwable>();
        var previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            unexpected.compareAndSet(null, failure);
            failure.printStackTrace(System.err);
        });
        try {
            SwingUtilities.invokeAndWait(() -> {
                AvailabilityTheme.install();
                var panel = AvailabilityPreviewFactory.panel();
                view.set(panel);
                var frame = new JFrame();
                window.set(frame);
                AvailabilityApplication.showWindow(frame, panel);
                frame.setTitle("VALIDATION PREVIEW — no network or private credentials");
                frame.setSize(720, 560);
                frame.toFront();
            });
            for (var field : List.of("identifier", "servers", "hichip")) {
                var focused = new CountDownLatch(1);
                var input = new AtomicReference<JComponent>();
                SwingUtilities.invokeAndWait(() -> {
                    var control = AvailabilityPreviewFactory.requestInvalidInput(view.get(), field);
                    input.set(control);
                    control.addFocusListener(new FocusAdapter() {
                        @Override public void focusGained(FocusEvent event) { focused.countDown(); }
                    });
                    if (control.isFocusOwner()) { focused.countDown(); }
                    require("error".equals(control.getClientProperty("JComponent.outline")), "Input not marked: " + field);
                });
                if (!layoutOnly && !focused.await(5, TimeUnit.SECONDS)) {
                    SwingUtilities.invokeAndWait(() -> System.err.println("Focus diagnostic: active=" + window.get().isActive()
                            + " target=" + input.get().getName() + " current=" + KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()));
                    throw new IllegalStateException("Validation focus was not acquired for " + field);
                }
                SwingUtilities.invokeAndWait(() -> {
                    var panel = view.get();
                    var frame = window.get();
                    frame.validate();
                    var control = input.get();
                    var message = find(panel, control.getName() + "Error").orElseThrow();
                    require(control.isShowing() && message.isShowing(), "Invalid field/message remains collapsed: " + field);
                    require(((JTextArea) message).getText().startsWith("Error: "), "Missing local error text");
                    require(message.getWidth() > 0 && message.getHeight() > 0, "Error message has no geometry");
                    require(control.getHeight() >= control.getMinimumSize().height, "Wrapped validation message squeezed its input: " + field
                            + " actual=" + control.getSize() + " minimum=" + control.getMinimumSize());
                    require(message.getHeight() >= message.getPreferredSize().height, "Validation message is clipped: " + field);
                    if (field.equals("hichip")) {
                        var section = find(panel, "hichipTools").orElseThrow();
                        var button = find(panel, "uidGenerate").orElseThrow();
                        var buttonBounds = SwingUtilities.convertRectangle(button.getParent(), button.getBounds(), section);
                        require(new java.awt.Rectangle(0, 0, section.getWidth(), section.getHeight()).contains(buttonBounds),
                                "Wrapped validation pushed the Build button outside its section");
                        require(button.getHeight() >= button.getMinimumSize().height, "Build button is clipped");
                    }
                    var scroll = (JScrollPane) frame.getContentPane();
                    require(!scroll.getHorizontalScrollBar().isVisible(), "Validation requires horizontal form scrolling");
                    var bounds = SwingUtilities.convertRectangle(control.getParent(), control.getBounds(), panel);
                    if (layoutOnly) { panel.scrollRectToVisible(bounds); }
                    require(scroll.getViewport().getViewRect().contains(bounds), "Invalid field is outside the viewport: " + field);
                    System.out.println("PASS: " + field + " field revealed, local error rendered, scrollable at 720x560"
                            + (layoutOnly ? " (focus not checked)" : ", focus acquired"));
                });
                if (unexpected.get() != null) { throw new IllegalStateException("Unexpected preview failure", unexpected.get()); }
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (view.get() != null) { view.get().close(); }
                if (window.get() != null) { window.get().dispose(); }
            });
            Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        }
    }

    private static Optional<JComponent> find(Container root, String name) {
        for (var child : root.getComponents()) {
            if (name.equals(child.getName()) && child instanceof JComponent component) { return Optional.of(component); }
            if (child instanceof Container nested) {
                var found = find(nested, name);
                if (found.isPresent()) { return found; }
            }
        }
        return Optional.empty();
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
