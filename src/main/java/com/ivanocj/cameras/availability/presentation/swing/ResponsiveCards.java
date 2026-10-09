package com.ivanocj.cameras.availability.presentation.swing;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.LayoutManager;
import java.util.function.IntSupplier;
import javax.swing.JComponent;
import javax.swing.JPanel;

/** Two existing cards laid out side-by-side or stacked; resizing never reparents their controls. */
final class ResponsiveCards extends JPanel implements LayoutManager {
    private static final int WIDE_WIDTH = 880;
    private static final int GAP = 12;
    private final JComponent connection;
    private final JComponent results;
    private final IntSupplier availableWidth;

    ResponsiveCards(JComponent connection, JComponent results, IntSupplier availableWidth) {
        super();
        this.connection = connection;
        this.results = results;
        this.availableWidth = availableWidth;
        setName("checkCards");
        setOpaque(false);
        setLayout(this);
        add(connection);
        add(results);
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        int width = availableWidth.getAsInt();
        if (width <= 0) { width = 928; }
        int first = connection.getPreferredSize().height;
        int second = results.getPreferredSize().height;
        return new Dimension(928, width >= WIDE_WIDTH ? Math.max(first, second) : first + GAP + second);
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        return new Dimension(0, preferredLayoutSize(parent).height);
    }

    @Override
    public void layoutContainer(Container parent) {
        int width = getWidth();
        boolean wide = width >= WIDE_WIDTH;
        int firstWidth = wide ? (width - GAP) / 2 : width;
        int firstHeight = connection.getPreferredSize().height;
        int secondHeight = results.getPreferredSize().height;
        connection.setBounds(0, 0, firstWidth, firstHeight);
        results.setBounds(wide ? firstWidth + GAP : 0, wide ? 0 : firstHeight + GAP,
                wide ? width - firstWidth - GAP : width, secondHeight);
    }

    @Override
    public void addLayoutComponent(String name, Component component) {
    }

    @Override
    public void removeLayoutComponent(Component component) {
    }
}
