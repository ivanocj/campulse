package com.ivanocj.cameras.availability.presentation.swing;

import com.ivanocj.cameras.availability.application.model.BatchEntry;
import static com.ivanocj.cameras.availability.presentation.swing.AvailabilityPanelTest.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class BatchResultsInspectionTest {
    @Test
    void filterCallbacksObserveSettledSelectionAndNeverTransientViewIndexes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var model = new BatchResultsTableModel();
            var table = new JTable(model);
            var inspection = new BatchResultsInspection(table, model);
            model.setEntries(List.of(new BatchEntry(2, "First", "", Optional.empty(), "Skipped"),
                    new BatchEntry(7, "Second", "", Optional.empty(), "Skipped")));
            table.setRowSelectionInterval(0, 0);
            var callback = new AtomicInteger();
            inspection.onFilterChanged(() -> {
                assertFalse(inspection.isFiltering());
                assertEquals(-1, table.getSelectedRow());
                callback.incrementAndGet();
            });
            table.getSelectionModel().addListSelectionListener(event -> {
                if (!event.getValueIsAdjusting()) { assertTrue(inspection.isFiltering()); }
            });
            field(inspection, "batchSearch", JTextField.class).setText("Second");
            assertEquals(1, callback.get());
            assertEquals("1 / 2 shown", field(inspection, "batchShownCount", JLabel.class).getText());
            assertEquals(7, table.getValueAt(0, 0));
        });
    }

    @Test
    void anEmptyModelAndZeroMatchesHaveExplicitCountsWithoutDiscardingData() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var model = new BatchResultsTableModel();
            var table = new JTable(model);
            var inspection = new BatchResultsInspection(table, model);
            assertEquals("0 / 0 shown", field(inspection, "batchShownCount", JLabel.class).getText());
            field(inspection, "batchSearch", JTextField.class).setText("Absent");
            model.setEntries(List.of(new BatchEntry(2, "First", "", Optional.empty(), "Skipped")));
            assertEquals("0 / 1 shown · No matches", field(inspection, "batchShownCount", JLabel.class).getText());
            assertEquals(1, model.getRowCount());
            model.setEntries(List.of());
            assertEquals("0 / 0 shown", field(inspection, "batchShownCount", JLabel.class).getText());
        });
    }
}
