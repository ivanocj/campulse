package com.ivanocj.cameras.availability.presentation.swing;

import com.ivanocj.cameras.availability.application.model.BatchEntry;
import com.ivanocj.cameras.availability.application.model.BatchResult;
import com.ivanocj.cameras.availability.presentation.AvailabilityPresenter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.swing.table.AbstractTableModel;

/** EDT-owned, read-only results keyed by source line so repeated DIDs keep separate rows. */
final class BatchResultsTableModel extends AbstractTableModel {
    static final int IDENTIFIER_COLUMN = 1;
    static final int DESCRIPTION_COLUMN = 2;
    static final int OUTCOME_COLUMN = 5;
    static final int ELAPSED_COLUMN = 11;
    private static final List<String> COLUMNS = List.of(
            "Line", "Device identifier", "Description", "Server profile", "Protocol", "Result", "Device IP / port",
            "Session endpoint", "Connection mode", "Client public endpoint", "Details", "Elapsed");
    private List<BatchEntry> entries = List.of();
    private final Map<Integer, Integer> rowsByLine = new HashMap<>();
    private final Map<Integer, BatchResult> resultsByLine = new HashMap<>();

    void setEntries(List<BatchEntry> entries) {
        this.entries = List.copyOf(entries);
        rowsByLine.clear();
        resultsByLine.clear();
        for (int row = 0; row < entries.size(); row++) {
            rowsByLine.put(entries.get(row).lineNumber(), row);
        }
        fireTableDataChanged();
    }

    void update(BatchResult result) {
        updateAll(List.of(result));
    }

    /** Stores every source-line snapshot before one sorter/table notification for the current UI chunk. */
    void updateAll(List<BatchResult> results) {
        int first = Integer.MAX_VALUE;
        int last = -1;
        for (var result : results) {
            int row = rowsByLine.get(result.entry().lineNumber());
            resultsByLine.put(result.entry().lineNumber(), result);
            first = Math.min(first, row);
            last = Math.max(last, row);
        }
        if (last >= 0) { fireTableRowsUpdated(first, last); }
    }

    Optional<BatchResult> resultAt(int row) {
        return row >= 0 && row < entries.size()
                ? Optional.ofNullable(resultsByLine.get(entries.get(row).lineNumber())) : Optional.empty();
    }

    /** Compares this model's unchanged display strings; missing values sort last ascending, first descending. */
    static int compareElapsed(String first, String second) {
        if (first.equals("—")) { return second.equals("—") ? 0 : 1; }
        if (second.equals("—")) { return -1; }
        return Long.compare(Long.parseLong(first.substring(0, first.length() - 3)),
                Long.parseLong(second.substring(0, second.length() - 3)));
    }

    @Override
    public int getRowCount() {
        return entries.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.size();
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS.get(column);
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return column == 0 ? Integer.class : String.class;
    }

    @Override
    public Object getValueAt(int row, int column) {
        var entry = entries.get(row);
        var result = resultAt(row);
        return switch (column) {
            case 0 -> entry.lineNumber();
            case 1 -> entry.code();
            case 2 -> entry.description();
            case 3 -> entry.profile().map(profile -> profile.name()).orElse("—");
            case 4 -> entry.profile().map(profile -> profile.protocol().toString()).orElse("—");
            case 5 -> result.map(AvailabilityPresenter::batchOutcome).orElse(entry.canCheck() ? "Queued" : "Skipped");
            case 6 -> result.flatMap(BatchResult::probeResult).map(AvailabilityPresenter::deviceEndpoints).orElse("—");
            case 7 -> result.flatMap(BatchResult::probeResult).flatMap(probe -> probe.peer())
                    .map(AvailabilityPresenter::endpoint).orElse("—");
            case 8 -> result.flatMap(BatchResult::probeResult).map(AvailabilityPresenter::connectionMode).orElse("—");
            case 9 -> result.flatMap(BatchResult::probeResult).flatMap(probe -> probe.publicEndpoint())
                    .map(AvailabilityPresenter::endpoint).orElse("—");
            case 10 -> result.map(BatchResult::detail).orElse(entry.error());
            case 11 -> result.flatMap(BatchResult::probeResult)
                    .map(probe -> probe.elapsed().toMillis() + " ms").orElse("—");
            default -> throw new IndexOutOfBoundsException("Unknown column: " + column);
        };
    }
}
