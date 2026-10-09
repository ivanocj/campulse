package com.ivanocj.cameras.availability.presentation.swing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/** Operation-owned FIFO delivery. Yield between bounded chunks; finish only after queued rendering settles. */
final class EdtEventDelivery<E> {
    private static final int EVENTS_PER_TURN = 128;
    private final ArrayDeque<E> pending = new ArrayDeque<>();
    private final int limit;
    private final Consumer<List<E>> render;
    private final Consumer<Runnable> later;
    private Runnable completion;
    private boolean scheduled;
    private boolean finished;

    EdtEventDelivery(Consumer<List<E>> render) {
        this(EVENTS_PER_TURN, render, SwingUtilities::invokeLater);
    }

    EdtEventDelivery(int limit, Consumer<List<E>> render, Consumer<Runnable> later) {
        if (limit < 1) { throw new IllegalArgumentException("The event limit must be positive."); }
        this.limit = limit;
        this.render = Objects.requireNonNull(render);
        this.later = Objects.requireNonNull(later);
    }

    void submit(List<E> events) {
        requireOpen();
        pending.addAll(events);
        if (!pending.isEmpty()) { schedule(); }
    }

    void finish(Runnable action) {
        requireOpen();
        if (completion != null) { throw new IllegalStateException("Completion is already queued."); }
        completion = Objects.requireNonNull(action);
        schedule();
    }

    private void requireOpen() {
        if (!SwingUtilities.isEventDispatchThread()) { throw new IllegalStateException("Delivery is EDT-owned."); }
        if (finished) { throw new IllegalStateException("Delivery is finished."); }
    }

    private void schedule() {
        if (!scheduled) {
            scheduled = true;
            later.accept(this::drain);
        }
    }

    private void drain() {
        var events = new ArrayList<E>(Math.min(limit, pending.size()));
        while (events.size() < limit && !pending.isEmpty()) { events.add(pending.removeFirst()); }
        try {
            if (!events.isEmpty()) { render.accept(List.copyOf(events)); }
        } finally {
            // A rendering exception still propagates on the EDT, but must not strand later delivery/cleanup.
            scheduled = false;
            if (!pending.isEmpty()) {
                schedule();
            } else if (completion != null) {
                finished = true;
                var action = completion;
                completion = null;
                action.run();
            }
        }
    }
}
