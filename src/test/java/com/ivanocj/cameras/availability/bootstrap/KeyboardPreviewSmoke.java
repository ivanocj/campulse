package com.ivanocj.cameras.availability.bootstrap;

import com.ivanocj.cameras.availability.presentation.swing.AvailabilityPanel;
import com.ivanocj.cameras.availability.presentation.swing.AvailabilityPreviewFactory;
import com.ivanocj.cameras.availability.presentation.swing.AvailabilityTheme;
import java.awt.Component;
import java.awt.Container;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/** Real-window focus/key-dispatch smoke check; synthetic JVM events only, never OS-wide Robot keys. */
public final class KeyboardPreviewSmoke {
    private KeyboardPreviewSmoke() {
    }

    public static void main(String[] args) throws Exception {
        boolean layoutOnly = args.length == 1 && args[0].equals("--layout-only");
        if (args.length > 0 && !layoutOnly) { throw new IllegalArgumentException("Use no arguments, or --layout-only."); }
        var identifierFocused = new CountDownLatch(1);
        var cancelFocused = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        var window = new AtomicReference<JFrame>();
        var view = new AtomicReference<AvailabilityPanel>();
        var focus = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        try {
            SwingUtilities.invokeAndWait(() -> {
                AvailabilityTheme.install();
                var panel = AvailabilityPreviewFactory.panel();
                view.set(panel);
                var did = component(panel, "did", JTextField.class);
                var cancel = component(panel, "cancel", JButton.class);
                focus.addPropertyChangeListener("focusOwner", event -> {
                    if (event.getNewValue() == did) { identifierFocused.countDown(); }
                    if (event.getNewValue() == cancel) { cancelFocused.countDown(); }
                });
                component(panel, "status", JLabel.class).addPropertyChangeListener("text", event -> {
                    if ("Cancelled".equals(event.getNewValue())) { completed.countDown(); }
                });
                var frame = new JFrame();
                window.set(frame);
                AvailabilityApplication.showWindow(frame, panel);
                frame.setTitle("KEYBOARD SMOKE PREVIEW — no network or private credentials");
                frame.toFront();
            });
            if (!layoutOnly && !identifierFocused.await(10, TimeUnit.SECONDS)) {
                SwingUtilities.invokeAndWait(() -> {
                    var did = component(view.get(), "did", JTextField.class);
                    System.err.println("Focus diagnostic: window=" + window.get().getBounds() + " active=" + window.get().isActive()
                            + " identifierShowing=" + did.isShowing() + " identifier=" + did.getBounds()
                            + " current=" + focus.getFocusOwner());
                });
                throw new IllegalStateException("Initial identifier focus was not acquired.");
            }
            SwingUtilities.invokeAndWait(() -> {
                var panel = view.get();
                var frame = window.get();
                var graphics = frame.getGraphicsConfiguration();
                var usable = WindowBounds.usable(graphics.getBounds(), Toolkit.getDefaultToolkit().getScreenInsets(graphics));
                require(usable.contains(frame.getBounds()), "Startup window exceeds usable screen bounds: " + frame.getBounds());
                System.out.println("Window bounds=" + frame.getBounds() + " usable=" + usable
                        + " display_scale=" + graphics.getDefaultTransform().getScaleX());
                var tracked = List.of("did", "serverProfile", "servers", "timeout", "transport", "lanSearch",
                        "credentialsFile", "clearCredentials", "batchParallelism", "check", "batchCheck",
                        "uidPrefix", "uidSerial", "uidGenerate", "resultContext");
                require(focusOrder(frame, panel, tracked).equals(List.of("did", "serverProfile", "check", "resultContext", "batchCheck", "batchParallelism")),
                        "Collapsed controls leaked into the keyboard cycle.");
                AvailabilityPreviewFactory.expandSections(panel);
                frame.validate();
                var expanded = List.of("did", "serverProfile", "check", "servers", "timeout", "transport", "lanSearch", "resultContext",
                        "batchCheck", "batchParallelism", "credentialsFile", "clearCredentials", "uidPrefix", "uidSerial", "uidGenerate");
                require(focusOrder(frame, panel, tracked).equals(expanded), "Unexpected expanded focus order: " + focusOrder(frame, panel, tracked));
                frame.setSize(720, 560);
                frame.validate();
                // Settle width-dependent preferred heights before querying scroll reachability in this EDT task.
                frame.invalidate();
                frame.validate();
                require(focusOrder(frame, panel, tracked).equals(expanded), "Narrow layout changed the keyboard cycle.");
            });
            // Allow Swing's queued width-dependent revalidation to settle, as it does after a user resize.
            SwingUtilities.invokeAndWait(() -> {
                var panel = view.get();
                var frame = window.get();
                var scroll = (JScrollPane) frame.getContentPane();
                require(!scroll.getHorizontalScrollBar().isVisible(), "Narrow layout requires horizontal scrolling.");
                var connection = component(panel, "connectionCard", JComponent.class);
                var result = component(panel, "resultCard", JComponent.class);
                require(result.getY() >= connection.getY() + connection.getHeight(), "Narrow cards did not stack.");
                for (var name : List.of("servers", "credentialsFile", "uidSerial", "p2pDetails", "log")) {
                    var control = component(panel, name, JComponent.class);
                    var bounds = SwingUtilities.convertRectangle(control.getParent(), control.getBounds(), panel);
                    panel.scrollRectToVisible(bounds);
                    require(control.isShowing(), "Expanded control is not reachable: " + name);
                    require(scroll.getViewport().getViewRect().contains(bounds), "Expanded control cannot scroll fully into view: " + name
                            + " control=" + bounds + " view=" + scroll.getViewport().getViewRect() + " panel=" + panel.getSize()
                            + " preferred=" + panel.getPreferredSize());
                }
                for (var name : List.of("connectionSettings", "accountSettings", "evidence", "hichipTools", "diagnostics")) {
                    component(panel, name + "Toggle", JToggleButton.class).doClick(0);
                }
                frame.validate();
                scroll.getViewport().setViewPosition(new java.awt.Point(0, 0));
                if (layoutOnly) {
                    System.out.println("PASS: native window bounds, collapsed/expanded traversal policies, narrow stacking, and scrollable controls.");
                    return;
                }
                component(panel, "did", JTextField.class).setText("FFFF-1-A");
                dispatch(frame, KeyEvent.VK_ENTER, 0, '\n');
                require("Checking…".equals(component(panel, "status", JLabel.class).getText()), "Enter did not start a check.");
                component(panel, "cancel", JButton.class).requestFocusInWindow();
            });
            if (layoutOnly) { return; }
            require(cancelFocused.await(5, TimeUnit.SECONDS), "Cancel focus was not acquired.");
            SwingUtilities.invokeAndWait(() -> {
                dispatch(window.get(), KeyEvent.VK_PERIOD, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx(), '.');
                require("Cancellation requested".equals(component(view.get(), "status", JLabel.class).getText()),
                        "The platform cancellation shortcut did not acknowledge cancellation.");
                require(!component(view.get(), "check", JButton.class).isEnabled(), "Inputs unlocked before cleanup.");
            });
            require(completed.await(5, TimeUnit.SECONDS), "Preview cleanup did not finish.");
            System.out.println("PASS: real-window initial focus, visual traversal order, Enter, platform cancel, and cleanup.");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (view.get() != null) { view.get().close(); }
                if (window.get() != null) { window.get().dispose(); }
            });
        }
    }

    private static void dispatch(JFrame frame, int keyCode, int modifiers, char character) {
        var focus = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        var owner = focus.getFocusOwner();
        require(frame.isActive() && owner != null && SwingUtilities.isDescendingFrom(owner, frame),
                "Refusing to dispatch a key outside this preview window.");
        long time = System.currentTimeMillis();
        focus.dispatchEvent(new KeyEvent(owner, KeyEvent.KEY_PRESSED, time, modifiers, keyCode, character));
        focus.dispatchEvent(new KeyEvent(owner, KeyEvent.KEY_RELEASED, time, modifiers, keyCode, character));
    }

    private static String logicalName(Component component, List<String> names) {
        for (var current = component; current != null; current = current.getParent()) {
            if (current.getName() != null && names.contains(current.getName())) { return current.getName(); }
        }
        return component == null ? "No focus target" : component.getClass().getSimpleName();
    }

    private static List<String> focusOrder(JFrame frame, AvailabilityPanel panel, List<String> tracked) {
        var result = new ArrayList<String>();
        Component first = component(panel, "did", JTextField.class);
        Component current = first;
        var policy = frame.getFocusTraversalPolicy();
        for (int step = 0; step < 200; step++) {
            String name = logicalName(current, tracked);
            if (tracked.contains(name)) { result.add(name); }
            current = policy.getComponentAfter(frame, current);
            if (current == first) { return result; }
            require(current != null, "Keyboard traversal lost its focus target.");
        }
        throw new IllegalStateException("Keyboard traversal did not cycle back to the identifier.");
    }

    private static <T extends Component> T component(Container root, String name, Class<T> type) {
        return find(root, name, type).orElseThrow(() -> new IllegalStateException("Missing component: " + name));
    }

    private static <T extends Component> Optional<T> find(Container root, String name, Class<T> type) {
        for (var child : root.getComponents()) {
            if (name.equals(child.getName())) { return Optional.of(type.cast(child)); }
            if (child instanceof Container nested) {
                var found = find(nested, name, type);
                if (found.isPresent()) { return found; }
            }
        }
        return Optional.empty();
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
