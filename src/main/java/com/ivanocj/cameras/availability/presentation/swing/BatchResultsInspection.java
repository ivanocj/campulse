package com.ivanocj.cameras.availability.presentation.swing;

import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.presentation.AvailabilityPresenter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.RowSorterEvent;
import javax.swing.table.TableRowSorter;

/** EDT-owned, display-only search/sorting. The underlying twelve-column model is never modified. */
final class BatchResultsInspection extends JPanel {
    private static final String ALL = "All outcomes";
    private static final List<String> OUTCOMES = Stream.concat(Stream.of(ALL, "Queued", "Skipped", "Check failed"),
            Arrays.stream(ProbeResult.Status.values()).map(status -> AvailabilityPresenter.completed(new ProbeResult(status)).headline()))
            .distinct().toList();
    private final BatchResultsTableModel model;
    private final TableRowSorter<BatchResultsTableModel> sorter;
    private final JTextField search = new JTextField(20);
    private final JComboBox<String> outcome = new JComboBox<>(OUTCOMES.toArray(String[]::new));
    private final JButton clear = new JButton("Show all");
    private final JLabel count = new JLabel();
    private boolean filtering;
    private Runnable filterChanged = () -> { };

    BatchResultsInspection(JTable table, BatchResultsTableModel model) {
        super(new BorderLayout(0, 6));
        this.model = model;
        setName("batchInspection");
        setOpaque(false);
        search.setName("batchSearch");
        outcome.setName("batchOutcomeFilter");
        clear.setName("clearBatchFilters");
        count.setName("batchShownCount");
        search.setMinimumSize(new Dimension(0, search.getPreferredSize().height));
        outcome.setMinimumSize(new Dimension(0, outcome.getPreferredSize().height));
        search.setToolTipText("Literal, case-insensitive text in identifiers or descriptions. Filters change display only and remain until cleared.");
        outcome.setToolTipText("Show an existing result category; testing and total batch counts are unchanged.");
        clear.setToolTipText("Clear search and outcome filters without testing again; keep the current sort order.");
        clear.setMnemonic(KeyEvent.VK_W);
        count.setForeground(AvailabilityTheme.MUTED);
        count.setToolTipText("Shown rows only; total batch counts still include every imported entry.");
        var queryRow = new JPanel(new BorderLayout(8, 0));
        queryRow.setOpaque(false);
        queryRow.add(label("Quick search · identifier / description", search, KeyEvent.VK_Q), BorderLayout.WEST);
        queryRow.add(search, BorderLayout.CENTER);
        var outcomeRow = new JPanel(new BorderLayout(8, 0));
        outcomeRow.setOpaque(false);
        outcomeRow.add(label("Outcome", outcome, KeyEvent.VK_M), BorderLayout.WEST);
        outcomeRow.add(outcome, BorderLayout.CENTER);
        outcomeRow.add(clear, BorderLayout.EAST);
        add(queryRow, BorderLayout.NORTH);
        add(outcomeRow, BorderLayout.CENTER);
        add(count, BorderLayout.SOUTH);

        sorter = new TableRowSorter<>(model);
        Comparator<String> elapsed = BatchResultsTableModel::compareElapsed;
        sorter.setComparator(BatchResultsTableModel.ELAPSED_COLUMN, elapsed);
        sorter.setSortsOnUpdates(true); // Completed rows must enter/leave an active outcome filter immediately.
        table.setRowSorter(sorter);
        table.getTableHeader().setToolTipText("Click a column to sort. Elapsed sorts numerically; unmeasured values sort last ascending and first descending.");
        sorter.addRowSorterListener(event -> {
            if (event.getType() == RowSorterEvent.Type.SORTED) { updateCount(); }
        });
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent event) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent event) { applyFilter(); }
        });
        // Consume Enter in the search field instead of invoking the window's default Check command.
        search.addActionListener(event -> applyFilter());
        outcome.addActionListener(event -> applyFilter());
        clear.addActionListener(event -> {
            search.setText("");
            outcome.setSelectedItem(ALL);
        });
        applyFilter();
    }

    void onFilterChanged(Runnable action) { filterChanged = action; }
    boolean isFiltering() { return filtering; }

    private void applyFilter() {
        String query = search.getText().toLowerCase(Locale.ROOT);
        String selected = (String) outcome.getSelectedItem();
        filtering = true;
        try {
            sorter.setRowFilter(new RowFilter<>() {
                @Override public boolean include(Entry<? extends BatchResultsTableModel, ? extends Integer> entry) {
                    return (query.isEmpty() || entry.getStringValue(BatchResultsTableModel.IDENTIFIER_COLUMN).toLowerCase(Locale.ROOT).contains(query)
                            || entry.getStringValue(BatchResultsTableModel.DESCRIPTION_COLUMN).toLowerCase(Locale.ROOT).contains(query))
                            && (ALL.equals(selected) || selected.equals(entry.getStringValue(BatchResultsTableModel.OUTCOME_COLUMN)));
                }
            });
        } finally {
            filtering = false;
        }
        updateCount();
        filterChanged.run();
    }

    private void updateCount() {
        int shown = sorter.getViewRowCount();
        int total = model.getRowCount();
        count.setText(shown + " / " + total + " shown" + (shown == 0 && total > 0 ? " · No matches" : ""));
    }

    private static JLabel label(String text, JComponent input, int mnemonic) {
        var label = new JLabel(text);
        label.setName(input.getName() + "Label");
        label.setLabelFor(input);
        label.setDisplayedMnemonic(mnemonic);
        label.setForeground(AvailabilityTheme.MUTED);
        return label;
    }
}
