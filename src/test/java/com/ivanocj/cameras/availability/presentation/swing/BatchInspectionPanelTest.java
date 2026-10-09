package com.ivanocj.cameras.availability.presentation.swing;

import com.ivanocj.cameras.availability.application.model.BatchEntry;
import com.ivanocj.cameras.availability.application.model.BatchResult;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.testsupport.TestServerConfigurationProvider;

import static com.ivanocj.cameras.availability.presentation.swing.AvailabilityPanelTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.awt.Rectangle;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BatchInspectionPanelTest {
    @TempDir Path directory;

    @Test
    void elapsedSortingIsNumericAndDefinesMissingPlacementWithoutChangingDisplayValues() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = unusedPanel();
            var table = table(panel);
            var model = model(panel);
            var entries = IntStream.rangeClosed(1, 7).mapToObj(line -> entry(line, "Camera " + line)).toList();
            model.setEntries(entries);
            model.update(checked(entries.get(0), ProbeResult.Status.TIMED_OUT, 100, "198.51.100.1"));
            model.update(checked(entries.get(1), ProbeResult.Status.TIMED_OUT, 2, "198.51.100.2"));
            model.update(checked(entries.get(2), ProbeResult.Status.TIMED_OUT, 10, "198.51.100.3"));
            model.update(new BatchResult(entries.get(4), BatchResult.State.FAILED, Optional.empty(), "Setup failed."));
            model.update(new BatchResult(entries.get(5), BatchResult.State.SKIPPED, Optional.empty(), "Skipped."));
            model.update(new BatchResult(entries.get(6), BatchResult.State.CANCELLED, Optional.empty(), "Not tested."));
            table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(11, SortOrder.ASCENDING)));
            assertEquals(List.of(2, 3, 1, 4, 5, 6, 7), lines(table));
            assertEquals(List.of("2 ms", "10 ms", "100 ms", "—", "—", "—", "—"),
                    IntStream.range(0, 7).mapToObj(row -> cell(table, row, "Elapsed")).toList());
            table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(11, SortOrder.DESCENDING)));
            assertEquals(List.of(4, 5, 6, 7, 1, 3, 2), lines(table));
            assertEquals(String.class, model.getColumnClass(11));
            assertEquals(12, table.getColumnCount());
            assertEquals(IntStream.rangeClosed(1, 7).boxed().toList(),
                    IntStream.range(0, 7).mapToObj(row -> model.getValueAt(row, 0)).toList());
        });
    }

    @Test
    void searchIsLiteralCaseInsensitiveAndPreservesRepeatedRowsAndUnicodeDescriptions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = unusedPanel();
            var model = model(panel);
            model.setEntries(List.of(entry(2, "Front door (north), \"quoted\""), entry(5, "Câmera entrada\nSecond floor"),
                    entry(9, "Literal .* marker")));
            var original = rows(model);
            var search = field(panel, "batchSearch", JTextField.class);
            search.setText("(north)");
            assertEquals(List.of(2), lines(table(panel)));
            assertEquals("1 / 3 shown", field(panel, "batchShownCount", JLabel.class).getText());
            search.setText("CÂMERA");
            assertEquals(List.of(5), lines(table(panel)));
            search.setText(".*");
            assertEquals(List.of(9), lines(table(panel)), "Regex metacharacters are literal text");
            search.setText("FFFF-1-A");
            assertEquals(3, table(panel).getRowCount(), "Repeated identifiers must not be merged");
            search.setText("no matches");
            assertEquals(0, table(panel).getRowCount());
            assertTrue(field(panel, "batchShownCount", JLabel.class).getText().startsWith("0 / 3 shown"));
            field(panel, "clearBatchFilters", JButton.class).doClick(0);
            assertEquals("", search.getText());
            assertEquals(List.of(2, 5, 9), lines(table(panel)));
            assertEquals(original, rows(model), "Filtering must not modify any of the twelve model values");
        });
    }

    @Test
    void outcomeChoicesMatchExistingLabelsWithoutCollapsingPresenceIntoReachability() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = unusedPanel();
            var model = model(panel);
            var entries = new ArrayList<BatchEntry>();
            for (int line = 1; line <= ProbeResult.Status.values().length + 3; line++) { entries.add(entry(line, "Camera")); }
            entries.add(new BatchEntry(entries.size() + 1, "broken", "Unknown route", Optional.empty(), "Not tested."));
            model.setEntries(entries);
            int row = 0;
            for (var status : ProbeResult.Status.values()) { model.update(checked(entries.get(row++), status, 10, "198.51.100.1")); }
            model.update(new BatchResult(entries.get(row++), BatchResult.State.FAILED, Optional.empty(), "Setup failed."));
            model.update(new BatchResult(entries.get(row), BatchResult.State.CANCELLED, Optional.empty(), "Not tested."));
            var outcomes = field(panel, "batchOutcomeFilter", JComboBox.class);
            for (int index = 0; index < entries.size(); index++) {
                String outcome = model.getValueAt(index, 5).toString();
                assertTrue(IntStream.range(0, outcomes.getItemCount()).anyMatch(item -> outcome.equals(outcomes.getItemAt(item))));
                outcomes.setSelectedItem(outcome);
                assertTrue(table(panel).getRowCount() > 0);
                for (int visible = 0; visible < table(panel).getRowCount(); visible++) { assertEquals(outcome, cell(table(panel), visible, "Result")); }
            }
            field(panel, "clearBatchFilters", JButton.class).doClick(0);
            assertEquals("All outcomes", outcomes.getSelectedItem());
            assertEquals(entries.size(), table(panel).getRowCount());
        });
    }

    @Test
    void sortedFilteredInspectionUsesSourceIdentityAndHidingASelectedRowRestoresTheGlobalSummary() throws Exception {
        var file = Files.writeString(directory.resolve("repeated.csv"), "Serial,Description\nFFFF-1-A,First camera\nFFFF-1-A,Second camera\nWWWW-2-B,Third camera\n");
        var done = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var view = new AtomicReference<AvailabilityPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = new AvailabilityPanel((request, cancelled, progress) -> {
                int call = calls.incrementAndGet();
                return probe(ProbeResult.Status.REACHABLE_P2P, call == 1 ? 100 : call == 2 ? 2 : 10, "198.51.100." + call);
            }, new TestServerConfigurationProvider());
            view.set(panel);
            prepareBatch(panel, file, "Batch complete", done);
            field(panel, "did", JTextField.class).setText("Keep this form input");
            field(panel, "servers", JTextArea.class).setText("192.0.2.40:45000");
            field(panel, "batchCheck", JButton.class).doClick(0);
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            var panel = view.get();
            String summary = field(panel, "batchSummary", JLabel.class).getText();
            var table = table(panel);
            table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(11, SortOrder.ASCENDING)));
            field(panel, "batchSearch", JTextField.class).setText("second");
            table.setRowSelectionInterval(0, 0);
            assertEquals("Batch row · repeated.csv · line 3\nFFFF-1-A\nSecond camera", field(panel, "resultContext", JTextArea.class).getText());
            assertEquals("198.51.100.2:32108", field(panel, "deviceEndpoint", JLabel.class).getText());
            field(panel, "clearBatchFilters", JButton.class).doClick(0);
            assertEquals(3, table.getValueAt(table.getSelectedRow(), 0), "Clearing a filter must preserve the selected source row");
            field(panel, "batchSearch", JTextField.class).setText("third");
            assertEquals(-1, table.getSelectedRow());
            assertEquals("Batch summary · repeated.csv", field(panel, "resultContext", JTextArea.class).getText());
            assertEquals("Batch complete", field(panel, "status", JLabel.class).getText());
            assertEquals("—", field(panel, "deviceEndpoint", JLabel.class).getText());
            assertEquals("", field(panel, "p2pDetails", JTextArea.class).getText());
            assertEquals(summary, field(panel, "batchSummary", JLabel.class).getText());
            assertTrue(summary.contains("3 reachable"));
            assertEquals("Keep this form input", field(panel, "did", JTextField.class).getText());
            assertEquals("192.0.2.40:45000", field(panel, "servers", JTextArea.class).getText());
            field(panel, "clearBatchFilters", JButton.class).doClick(0);
            table.setRowSelectionInterval(0, 0);
            table.clearSelection();
            assertEquals("Batch summary · repeated.csv", field(panel, "resultContext", JTextArea.class).getText());
            assertEquals(3, calls.get(), "Inspecting and clearing filters must not start another probe");
        });
    }

    @Test
    void completionAndCancellationReapplyActiveFiltersWithoutChangingSummaryOrCleanup() throws Exception {
        var file = Files.writeString(directory.resolve("devices.csv"), "Serial,Description\nFFFF-1-A,Camera one\nWWWW-2-B,Camera two\nTTTT-3-C,Camera three\n");
        var secondEntered = new CountDownLatch(1);
        var firstVisible = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var view = new AtomicReference<AvailabilityPanel>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                var panel = new AvailabilityPanel((request, cancelled, progress) -> {
                    if (calls.incrementAndGet() == 1) { return probe(ProbeResult.Status.REACHABLE_P2P, 2, "198.51.100.1"); }
                    secondEntered.countDown();
                    await(release);
                    assertTrue(cancelled.getAsBoolean());
                    return new ProbeResult(ProbeResult.Status.CANCELLED);
                }, new TestServerConfigurationProvider());
                view.set(panel);
                prepareBatch(panel, file, "Batch cancelled", done);
                field(panel, "batchOutcomeFilter", JComboBox.class).setSelectedItem("Reachable · direct P2P");
                field(panel, "batchShownCount", JLabel.class).addPropertyChangeListener("text", event -> {
                    if ("1 / 3 shown".equals(event.getNewValue())) { firstVisible.countDown(); }
                });
                field(panel, "batchCheck", JButton.class).doClick(0);
            });
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
            assertTrue(firstVisible.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                var panel = view.get();
                assertEquals(1, table(panel).getRowCount());
                var outcomes = field(panel, "batchOutcomeFilter", JComboBox.class);
                assertTrue(outcomes.isEnabled());
                assertTrue(field(panel, "batchSearch", JTextField.class).isEnabled());
                outcomes.setSelectedItem("Cancelled");
                assertEquals(0, table(panel).getRowCount());
                field(panel, "cancel", JButton.class).doClick(0);
                field(panel, "batchSearch", JTextField.class).setText("camera");
                assertEquals("Cancellation requested", field(panel, "status", JLabel.class).getText());
                assertFalse(field(panel, "check", JButton.class).isEnabled());
            });
        } finally {
            release.countDown();
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            var panel = view.get();
            assertEquals("2 / 3 shown", field(panel, "batchShownCount", JLabel.class).getText());
            assertEquals(List.of(3, 4), lines(table(panel)));
            assertTrue(field(panel, "batchSummary", JLabel.class).getText().contains("1 reachable"));
            assertTrue(field(panel, "batchSummary", JLabel.class).getText().contains("2 cancelled"));
            assertTrue(field(panel, "check", JButton.class).isEnabled());
            field(panel, "clearBatchFilters", JButton.class).doClick(0);
            assertEquals(3, table(panel).getRowCount());
            assertEquals(2, calls.get());
        });
    }

    @Test
    void searchEnterAndShowAllAreDisplayOnlyAndFieldsHaveKeyboardLabels() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = unusedPanel();
            var root = new JRootPane();
            root.getContentPane().add(panel);
            panel.addNotify();
            var search = field(panel, "batchSearch", JTextField.class);
            assertTrue(search.getActionListeners().length > 0, "Enter must be consumed by search, not default Check");
            search.setText("literal");
            search.postActionEvent();
            field(panel, "clearBatchFilters", JButton.class).doClick(0);
            assertEquals("Ready to check", field(panel, "status", JLabel.class).getText());
            assertSame(field(panel, "check", JButton.class), root.getDefaultButton());
            for (String name : List.of("batchSearch", "batchOutcomeFilter")) {
                var label = field(panel, name + "Label", JLabel.class);
                assertSame(field(panel, name, javax.swing.JComponent.class), label.getLabelFor());
                assertTrue(label.getDisplayedMnemonicIndex() >= 0);
            }
        });
    }

    @Test
    void aReplacementImportRetainsTheFilterButClearsOldRowsAndResults() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = unusedPanel();
            var old = entry(2, "Match old camera");
            model(panel).setEntries(List.of(old));
            model(panel).update(checked(old, ProbeResult.Status.REACHABLE_P2P, 2, "198.51.100.1"));
            field(panel, "batchSearch", JTextField.class).setText("match");
            field(panel, "batchOutcomeFilter", JComboBox.class).setSelectedItem("Reachable · direct P2P");
            assertEquals(1, table(panel).getRowCount());
            var next = entry(2, "Match new camera");
            model(panel).setEntries(List.of(next));
            assertEquals(0, table(panel).getRowCount());
            assertTrue(model(panel).resultAt(0).isEmpty());
            model(panel).update(checked(next, ProbeResult.Status.REACHABLE_P2P, 10, "198.51.100.2"));
            assertEquals(1, table(panel).getRowCount());
            assertEquals("Match new camera", cell(table(panel), 0, "Description"));
            assertEquals("match", field(panel, "batchSearch", JTextField.class).getText());
            assertEquals("1 / 1 shown", field(panel, "batchShownCount", JLabel.class).getText());
        });
    }

    @Test
    void aLargeSyntheticListCanBeFoundAndRestoredWithoutLosingRows() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = unusedPanel();
            model(panel).setEntries(IntStream.rangeClosed(1, 5000).mapToObj(line -> new BatchEntry(line,
                    "FFFF-" + line + "-A", "Câmera " + line + (line == 4096 ? " · north [door]" : ""),
                    entry(1, "").profile(), "")).toList());
            field(panel, "batchSearch", JTextField.class).setText("FFFF-4096-A");
            assertEquals(List.of(4096), lines(table(panel)));
            field(panel, "batchSearch", JTextField.class).setText("north [door]");
            assertEquals(List.of(4096), lines(table(panel)));
            assertEquals("1 / 5000 shown", field(panel, "batchShownCount", JLabel.class).getText());
            field(panel, "clearBatchFilters", JButton.class).doClick(0);
            assertEquals(5000, table(panel).getRowCount());
            assertEquals(5000, model(panel).getRowCount());
        });
    }

    @Test
    void changingFiltersAfterASingleCheckDoesNotRelabelItsResultAsAnOldBatchSelection() throws Exception {
        var file = Files.writeString(directory.resolve("previous.csv"), "Serial,Description\nFFFF-1-A,Old camera\n");
        var batchDone = new CountDownLatch(1);
        var singleDone = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var view = new AtomicReference<AvailabilityPanel>();
        SwingUtilities.invokeAndWait(() -> {
            var panel = new AvailabilityPanel((request, cancelled, progress) -> {
                calls.incrementAndGet();
                return probe(ProbeResult.Status.REACHABLE_P2P, 2, "198.51.100.1");
            }, new TestServerConfigurationProvider());
            view.set(panel);
            prepareBatch(panel, file, "Batch complete", batchDone);
            field(panel, "batchCheck", JButton.class).doClick(0);
        });
        assertTrue(batchDone.await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            var panel = view.get();
            table(panel).setRowSelectionInterval(0, 0);
            field(panel, "status", JLabel.class).addPropertyChangeListener("text", event -> {
                if ("Reachable · direct P2P".equals(event.getNewValue())) { singleDone.countDown(); }
            });
            field(panel, "did", JTextField.class).setText("FFFF-2-B");
            field(panel, "check", JButton.class).doClick(0);
        });
        assertTrue(singleDone.await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
            var panel = view.get();
            field(panel, "batchSearch", JTextField.class).setText("absent");
            field(panel, "clearBatchFilters", JButton.class).doClick(0);
            assertEquals("Single check · PPCS\nFFFF-2-B", field(panel, "resultContext", JTextArea.class).getText());
            assertEquals("Reachable · direct P2P", field(panel, "status", JLabel.class).getText());
            assertEquals("198.51.100.1:32108", field(panel, "deviceEndpoint", JLabel.class).getText());
            assertEquals(2, calls.get());
        });
    }

    @Test
    void batchInspectionControlsFitAndScrollTogetherInANarrowViewport() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = unusedPanel();
            field(panel, "batchCard", javax.swing.JComponent.class).setVisible(true);
            model(panel).setEntries(List.of(entry(2, "Camera")));
            var scroll = new JScrollPane(panel);
            scroll.setSize(720, 560);
            AvailabilityLayoutTest.layout(scroll);
            AvailabilityLayoutTest.layout(scroll);
            var inspection = field(panel, "batchInspection", javax.swing.JComponent.class);
            assertFalse(scroll.getHorizontalScrollBar().isVisible());
            for (String name : List.of("batchSearch", "batchOutcomeFilter", "clearBatchFilters", "batchShownCount")) {
                var control = field(panel, name, javax.swing.JComponent.class);
                assertTrue(control.getHeight() >= control.getMinimumSize().height);
                assertTrue(new Rectangle(0, 0, inspection.getWidth(), inspection.getHeight()).contains(
                        SwingUtilities.convertRectangle(control.getParent(), control.getBounds(), inspection)), name);
            }
            var bounds = SwingUtilities.convertRectangle(inspection.getParent(), inspection.getBounds(), panel);
            panel.scrollRectToVisible(bounds);
            assertTrue(scroll.getViewport().getViewRect().contains(bounds));
        });
    }

    private static AvailabilityPanel unusedPanel() {
        return new AvailabilityPanel((request, cancelled, progress) -> { throw new AssertionError("Inspection must not run probes"); },
                new TestServerConfigurationProvider());
    }

    private static BatchEntry entry(int line, String description) {
        return new BatchEntry(line, "FFFF-1-A", description,
                Optional.of(TestServerConfigurationProvider.profileNamed("HIP2P · XQ FFFF/HHHH/GGGG")), "");
    }

    private static ProbeResult probe(ProbeResult.Status status, long millis, String address) {
        return new ProbeResult(status, "Original result.", true, Optional.of(new InetSocketAddress(address, 32108)),
                Optional.empty(), Duration.ofMillis(millis));
    }

    private static BatchResult checked(BatchEntry entry, ProbeResult.Status status, long millis, String address) {
        var probe = probe(status, millis, address);
        return new BatchResult(entry, status == ProbeResult.Status.CANCELLED ? BatchResult.State.CANCELLED : BatchResult.State.CHECKED,
                Optional.of(probe), probe.detail());
    }

    private static JTable table(AvailabilityPanel panel) { return field(panel, "batchResults", JTable.class); }
    private static BatchResultsTableModel model(AvailabilityPanel panel) { return (BatchResultsTableModel) table(panel).getModel(); }
    private static Object cell(JTable table, int row, String name) { return table.getValueAt(row, table.getColumnModel().getColumnIndex(name)); }
    private static List<Object> lines(JTable table) { return IntStream.range(0, table.getRowCount()).mapToObj(row -> cell(table, row, "Line")).toList(); }
    private static List<List<Object>> rows(BatchResultsTableModel model) {
        return IntStream.range(0, model.getRowCount()).mapToObj(row -> IntStream.range(0, model.getColumnCount())
                .mapToObj(column -> model.getValueAt(row, column)).toList()).toList();
    }

    private static void prepareBatch(AvailabilityPanel panel, Path file, String headline, CountDownLatch done) {
        panel.chooseBatchFile = () -> Optional.of(file);
        field(panel, "batchParallelism", JSpinner.class).setValue(1);
        field(panel, "status", JLabel.class).addPropertyChangeListener("text", event -> {
            if (headline.equals(event.getNewValue())) { done.countDown(); }
        });
    }

    private static void await(CountDownLatch release) throws IOException {
        try {
            if (!release.await(5, TimeUnit.SECONDS)) { throw new IOException("Test did not release the fake probe"); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }
}
