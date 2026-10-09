package com.ivanocj.cameras.availability.presentation.swing;

import com.ivanocj.cameras.availability.application.model.BatchEntry;
import com.ivanocj.cameras.availability.application.model.BatchResult;
import com.ivanocj.cameras.availability.application.model.P2pInformation;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.testsupport.TestServerConfigurationProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;
import javax.swing.event.TableModelEvent;
import org.junit.jupiter.api.Test;

class BatchResultsTableModelTest {
    @Test
    void theTwelveColumnsAreReadOnlyAndKeepTheirCurrentTypes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var model = new BatchResultsTableModel();
            model.setEntries(List.of(entry(2, "Camera")));
            assertEquals(List.of("Line", "Device identifier", "Description", "Server profile", "Protocol", "Result",
                    "Device IP / port", "Session endpoint", "Connection mode", "Client public endpoint", "Details", "Elapsed"),
                    IntStream.range(0, model.getColumnCount()).mapToObj(model::getColumnName).toList());
            for (int column = 0; column < model.getColumnCount(); column++) {
                assertEquals(column == 0 ? Integer.class : String.class, model.getColumnClass(column));
                assertFalse(model.isCellEditable(0, column));
            }
            assertThrows(IndexOutOfBoundsException.class, () -> model.getValueAt(0, model.getColumnCount()));
        });
    }

    @Test
    void queuedAndSkippedRowsRetainDescriptionsAndHaveNoInventedNetworkEvidence() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var model = new BatchResultsTableModel();
            model.setEntries(List.of(entry(2, "Câmera entrada"),
                    new BatchEntry(5, "MISSING-1-A", "Unknown deployment", Optional.empty(), "No known server profile")));
            assertEquals(List.of(2, "FFFF-1-A", "Câmera entrada", "HIP2P · XQ FFFF/HHHH/GGGG", "XQ", "Queued",
                    "—", "—", "—", "—", "", "—"), row(model, 0));
            assertEquals(List.of(5, "MISSING-1-A", "Unknown deployment", "—", "—", "Skipped",
                    "—", "—", "—", "—", "No known server profile", "—"), row(model, 1));
            assertTrue(model.resultAt(-1).isEmpty());
            assertTrue(model.resultAt(0).isEmpty());
            assertTrue(model.resultAt(2).isEmpty());
        });
    }

    @Test
    void repeatedIdentifiersKeepIndependentRowsAndAllDirectAndRelayValues() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var first = entry(2, "First camera");
            var second = entry(8, "Second camera");
            var model = new BatchResultsTableModel();
            model.setEntries(List.of(first, second));
            var relay = checked(second, ProbeResult.Status.REACHABLE_RELAY, "192.0.2.8");
            model.update(relay);
            assertEquals("Queued", model.getValueAt(0, 5));
            var direct = checked(first, ProbeResult.Status.REACHABLE_P2P, "198.51.100.8");
            model.update(direct);
            assertSame(direct, model.resultAt(0).orElseThrow());
            assertSame(relay, model.resultAt(1).orElseThrow());
            assertEquals(List.of(2, "FFFF-1-A", "First camera", "HIP2P · XQ FFFF/HHHH/GGGG", "XQ",
                    "Reachable · direct P2P", "198.51.100.8:32108", "198.51.100.8:32108", "Direct P2P",
                    "203.0.113.4:55000", "Confirmed.", "400 ms"), row(model, 0));
            assertEquals(List.of(8, "FFFF-1-A", "Second camera", "HIP2P · XQ FFFF/HHHH/GGGG", "XQ",
                    "Reachable · UDP relay", "Not returned", "192.0.2.8:32108", "UDP relay",
                    "203.0.113.4:55000", "Confirmed.", "400 ms"), row(model, 1));
        });
    }

    @Test
    void cancellationRetainsProbeCandidatesButUntestedAndFailedRowsHaveNoEndpoints() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var active = entry(2, "Cancelled probe");
            var pending = entry(3, "Not started");
            var failed = entry(4, "Failed probe");
            var model = new BatchResultsTableModel();
            model.setEntries(List.of(active, pending, failed));
            var information = new P2pInformation(Optional.of(active.profile().orElseThrow().protocol()),
                    P2pInformation.ConnectionMode.NONE, Optional.empty(), List.of(), List.of(),
                    List.of(new InetSocketAddress("198.51.100.8", 32108)), List.of(), Map.of());
            var probe = new ProbeResult(ProbeResult.Status.CANCELLED, "Check cancelled.", false,
                    Optional.empty(), Optional.empty(), Duration.ofMillis(20), information);
            model.update(new BatchResult(active, BatchResult.State.CANCELLED, Optional.of(probe), probe.detail()));
            model.update(new BatchResult(pending, BatchResult.State.CANCELLED, Optional.empty(), "Not tested."));
            model.update(new BatchResult(failed, BatchResult.State.FAILED, Optional.empty(), "Socket setup failed."));
            assertEquals("Cancelled", model.getValueAt(0, 5));
            assertEquals("198.51.100.8:32108", model.getValueAt(0, 6));
            assertEquals("Not established", model.getValueAt(0, 8));
            assertEquals("20 ms", model.getValueAt(0, 11));
            assertEquals("Cancelled", model.getValueAt(1, 5));
            assertEquals("Check failed", model.getValueAt(2, 5));
            for (int row = 1; row < model.getRowCount(); row++) {
                for (int column : new int[] {6, 7, 8, 9, 11}) {
                    assertEquals("—", model.getValueAt(row, column));
                }
            }
            assertEquals("Not tested.", model.getValueAt(1, 10));
            assertEquals("Socket setup failed.", model.getValueAt(2, 10));
        });
    }

    @Test
    void aNewImportClearsPreviousResultsEvenWhenItsSourceLinesAreReused() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var model = new BatchResultsTableModel();
            var old = entry(2, "Old camera");
            model.setEntries(List.of(old));
            model.update(checked(old, ProbeResult.Status.REACHABLE_P2P, "198.51.100.8"));
            model.setEntries(List.of(entry(2, "New camera")));
            assertTrue(model.resultAt(0).isEmpty());
            assertEquals("New camera", model.getValueAt(0, 2));
            assertEquals("Queued", model.getValueAt(0, 5));
            assertEquals("—", model.getValueAt(0, 6));
            model.setEntries(List.of());
            assertEquals(0, model.getRowCount());
            assertTrue(model.resultAt(0).isEmpty());
        });
    }

    @Test
    void elapsedComparisonUsesLongPrecisionAndOrdersUnmeasuredValuesLastAscending() {
        assertTrue(BatchResultsTableModel.compareElapsed("2 ms", "10 ms") < 0);
        assertTrue(BatchResultsTableModel.compareElapsed("10 ms", "100 ms") < 0);
        assertTrue(BatchResultsTableModel.compareElapsed("9223372036854775806 ms", "9223372036854775807 ms") < 0);
        assertTrue(BatchResultsTableModel.compareElapsed("0 ms", "—") < 0);
        assertTrue(BatchResultsTableModel.compareElapsed("—", "100 ms") > 0);
        assertEquals(0, BatchResultsTableModel.compareElapsed("—", "—"));
        assertEquals(0, BatchResultsTableModel.compareElapsed("10 ms", "10 ms"));
    }

    @Test
    void bulkUpdatesKeepEverySnapshotAndNotifyOneInclusiveSourceRowRange() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var first = entry(2, "First repeated identifier");
            var untouched = entry(3, "Still queued");
            var last = entry(8, "Second repeated identifier");
            var model = new BatchResultsTableModel();
            model.setEntries(List.of(first, untouched, last));
            var events = new ArrayList<TableModelEvent>();
            model.addTableModelListener(events::add);
            var relay = checked(last, ProbeResult.Status.REACHABLE_RELAY, "192.0.2.8");
            var direct = checked(first, ProbeResult.Status.REACHABLE_P2P, "198.51.100.8");
            model.updateAll(List.of(relay, direct));
            assertSame(direct, model.resultAt(0).orElseThrow());
            assertTrue(model.resultAt(1).isEmpty());
            assertSame(relay, model.resultAt(2).orElseThrow());
            assertEquals(1, events.size());
            assertEquals(0, events.getFirst().getFirstRow());
            assertEquals(2, events.getFirst().getLastRow());
            model.updateAll(List.of());
            assertEquals(1, events.size(), "An empty chunk must not re-sort the table");
        });
    }

    @Test
    void bulkUpdatesRefilterAndSortOnceWithoutLosingASelectedRepeatedSourceRow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var model = new BatchResultsTableModel();
            var table = new JTable(model);
            var inspection = new BatchResultsInspection(table, model);
            var first = entry(2, "First repeated identifier");
            var last = entry(8, "Second repeated identifier");
            model.setEntries(List.of(first, last));
            AvailabilityPreviewFactory.component(inspection, "batchOutcomeFilter", JComboBox.class)
                    .setSelectedItem("Reachable · direct P2P");
            table.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(11, SortOrder.ASCENDING)));
            model.updateAll(List.of(checked(last, ProbeResult.Status.REACHABLE_P2P, "198.51.100.8")));
            assertEquals(1, table.getRowCount());
            table.setRowSelectionInterval(0, 0);
            model.updateAll(List.of(checked(first, ProbeResult.Status.REACHABLE_P2P, "198.51.100.2")));
            assertEquals(2, table.getRowCount());
            assertEquals(8, table.getValueAt(table.getSelectedRow(), 0));
            assertEquals("2 / 2 shown", AvailabilityPreviewFactory.component(inspection, "batchShownCount", JLabel.class).getText());
        });
    }

    private static BatchEntry entry(int line, String description) {
        return new BatchEntry(line, "FFFF-1-A", description,
                Optional.of(TestServerConfigurationProvider.profileNamed("HIP2P · XQ FFFF/HHHH/GGGG")), "");
    }

    private static BatchResult checked(BatchEntry entry, ProbeResult.Status status, String peer) {
        var probe = new ProbeResult(status, "Confirmed.", true,
                Optional.of(new InetSocketAddress(peer, 32108)),
                Optional.of(new InetSocketAddress("203.0.113.4", 55000)), Duration.ofMillis(400));
        return new BatchResult(entry, BatchResult.State.CHECKED, Optional.of(probe), probe.detail());
    }

    private static List<Object> row(BatchResultsTableModel model, int row) {
        return IntStream.range(0, model.getColumnCount()).mapToObj(column -> model.getValueAt(row, column)).toList();
    }
}
