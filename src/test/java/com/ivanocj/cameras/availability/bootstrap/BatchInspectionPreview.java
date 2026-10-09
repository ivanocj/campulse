package com.ivanocj.cameras.availability.bootstrap;

import com.ivanocj.cameras.availability.presentation.swing.AvailabilityPanel;
import com.ivanocj.cameras.availability.presentation.swing.AvailabilityPreviewFactory;
import com.ivanocj.cameras.availability.presentation.swing.AvailabilityTheme;
import static com.ivanocj.cameras.availability.presentation.swing.AvailabilityPreviewFactory.component;

import java.awt.Dimension;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;

/** Actual batch actions with synthetic results and a local fixture; never probes or reads credentials. */
public final class BatchInspectionPreview {
    private BatchInspectionPreview() {
    }

    public static void main(String[] args) throws Exception {
        boolean snapshot = args.length >= 1 && args[0].equals("--snapshot");
        boolean layoutOnly = args.length == 2 && args[0].equals("--smoke") && args[1].equals("--layout-only");
        boolean smoke = layoutOnly || args.length == 1 && args[0].equals("--smoke");
        String mode = args.length == 2 ? args[1] : "wide";
        if (args.length > 0 && !smoke && (!snapshot || args.length > 2 || !List.of("wide", "small", "filtered", "empty").contains(mode))) {
            throw new IllegalArgumentException("Use no arguments, --smoke [--layout-only], or --snapshot [wide|small|filtered|empty].");
        }
        var fixture = AvailabilityPreviewFactory.batchFixture();
        var view = new AtomicReference<AvailabilityPanel>();
        var window = new AtomicReference<JFrame>();
        var done = new CountDownLatch(1);
        var unexpected = new AtomicReference<Throwable>();
        var previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            unexpected.compareAndSet(null, failure);
            failure.printStackTrace(System.err);
        });
        try {
            SwingUtilities.invokeAndWait(() -> {
                AvailabilityTheme.install();
                var panel = AvailabilityPreviewFactory.batchPanel(fixture);
                view.set(panel);
                component(panel, "status", JLabel.class).addPropertyChangeListener("text", event -> {
                    if ("Batch complete".equals(event.getNewValue())) { done.countDown(); }
                });
                if (!snapshot) {
                    var frame = new JFrame();
                    window.set(frame);
                    AvailabilityApplication.showWindow(frame, panel);
                    frame.setTitle("BATCH PREVIEW — synthetic results, no network or private credentials");
                    if (smoke) { frame.setSize(720, 560); frame.toFront(); }
                }
                component(panel, "batchCheck", JButton.class).doClick(0);
            });
            require(done.await(10, TimeUnit.SECONDS), "Synthetic batch did not complete");
            if (snapshot) {
                SwingUtilities.invokeAndWait(() -> {
                    var panel = view.get();
                    var table = component(panel, "batchResults", JTable.class);
                    // JTable normally installs its header on addNotify; offscreen previews have no native peer.
                    ((JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, table)).setColumnHeaderView(table.getTableHeader());
                    table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(elapsedColumn(table), SortOrder.ASCENDING)));
                    if (mode.equals("filtered")) {
                        component(panel, "batchSearch", JTextField.class).setText("north");
                        table.setRowSelectionInterval(1, 1);
                    } else if (mode.equals("empty")) {
                        component(panel, "batchSearch", JTextField.class).setText("no matching entries");
                    }
                    try {
                        AvailabilityPreview.snapshot(panel, "availability-batch-" + mode + ".png",
                                mode.equals("small") ? new Dimension(720, 560) : new Dimension(1100, 1080));
                    } catch (IOException failed) { throw new UncheckedIOException(failed); }
                });
            } else if (smoke) {
                smoke(window.get(), view.get(), layoutOnly);
            }
            if (unexpected.get() != null) { throw new IllegalStateException("Unexpected asynchronous preview failure", unexpected.get()); }
        } finally {
            if (snapshot || smoke || unexpected.get() != null) {
                SwingUtilities.invokeAndWait(() -> {
                    if (view.get() != null) { view.get().close(); }
                    if (window.get() != null) { window.get().dispose(); }
                });
            }
            Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        }
    }

    private static void smoke(JFrame frame, AvailabilityPanel panel, boolean layoutOnly) throws Exception {
        var focused = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> {
            var search = component(panel, "batchSearch", JTextField.class);
            search.addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent event) { focused.countDown(); }
            });
            if (!layoutOnly) { frame.toFront(); search.requestFocusInWindow(); }
            if (search.isFocusOwner()) { focused.countDown(); }
        });
        if (!layoutOnly && !focused.await(5, TimeUnit.SECONDS)) {
            SwingUtilities.invokeAndWait(() -> System.err.println("Focus diagnostic: active=" + frame.isActive()
                    + " searchShowing=" + component(panel, "batchSearch", JTextField.class).isShowing()
                    + " owner=" + KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()));
            throw new IllegalStateException("Batch search focus was not acquired");
        }
        SwingUtilities.invokeAndWait(() -> {
            String summary = component(panel, "batchSummary", JLabel.class).getText();
            var table = component(panel, "batchResults", JTable.class);
            require(table.getTableHeader().isShowing(), "Batch column headers are not available for sorting");
            var search = component(panel, "batchSearch", JTextField.class);
            search.setText("north");
            if (layoutOnly) { search.postActionEvent(); }
            else { dispatchEnter(frame, search); }
            require("Batch complete".equals(component(panel, "status", JLabel.class).getText()), "Search Enter invoked the default Check command");
            require(table.getRowCount() == 2 && table.getColumnCount() == 12, "Filtered rows/columns changed");
            table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(elapsedColumn(table), SortOrder.ASCENDING)));
            table.setRowSelectionInterval(1, 1);
            require(component(panel, "resultContext", JTextArea.class).getText().contains("Câmera entrada [north]"), "Wrong repeated-ID source row inspected");
            search.setText("Garage");
            require(table.getSelectedRow() == -1, "Filtered-out selection was reassigned");
            require(component(panel, "resultContext", JTextArea.class).getText().startsWith("Batch summary"), "Hidden row evidence was retained");
            require(summary.equals(component(panel, "batchSummary", JLabel.class).getText()), "Filtering changed global batch counts");
            component(panel, "clearBatchFilters", JButton.class).doClick(0);
            require(table.getRowCount() == 7, "Show all did not restore every entry");
            require("7 / 7 shown".equals(component(panel, "batchShownCount", JLabel.class).getText()), "Shown/total count is wrong");
            frame.validate();
            var inspection = component(panel, "batchInspection", JComponent.class);
            var bounds = SwingUtilities.convertRectangle(inspection.getParent(), inspection.getBounds(), panel);
            panel.scrollRectToVisible(bounds);
            var scroll = (JScrollPane) frame.getContentPane();
            require(!scroll.getHorizontalScrollBar().isVisible(), "Main form needs horizontal scrolling at 720x560");
            require(scroll.getViewport().getViewRect().contains(bounds), "Filter controls cannot be revealed together");
            for (var name : List.of("batchSearch", "batchOutcomeFilter", "clearBatchFilters", "batchShownCount")) {
                var control = component(panel, name, JComponent.class);
                Rectangle local = SwingUtilities.convertRectangle(control.getParent(), control.getBounds(), inspection);
                require(control.isShowing() && control.getHeight() >= control.getMinimumSize().height
                        && new Rectangle(0, 0, inspection.getWidth(), inspection.getHeight()).contains(local), "Filter control is clipped: " + name);
            }
            System.out.println("PASS: native 720x560 filter geometry/scrolling, repeated-ID inspection, hidden-selection clearing, and unchanged global summary; "
                    + (layoutOnly ? "field actions only (focus/key dispatch not tested)." : "focus and guarded search Enter verified."));
        });
    }

    private static int elapsedColumn(JTable table) {
        return table.convertColumnIndexToModel(table.getColumnModel().getColumnIndex("Elapsed"));
    }

    private static void dispatchEnter(JFrame frame, JTextField search) {
        var focus = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        require(frame.isActive() && focus.getFocusOwner() == search, "Refusing key dispatch outside the focused preview search field");
        long time = System.currentTimeMillis();
        focus.dispatchEvent(new KeyEvent(search, KeyEvent.KEY_PRESSED, time, 0, KeyEvent.VK_ENTER, '\n'));
        focus.dispatchEvent(new KeyEvent(search, KeyEvent.KEY_RELEASED, time, 0, KeyEvent.VK_ENTER, '\n'));
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new IllegalStateException(message); }
    }
}
