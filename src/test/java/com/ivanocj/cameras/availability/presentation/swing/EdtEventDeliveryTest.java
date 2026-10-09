package com.ivanocj.cameras.availability.presentation.swing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class EdtEventDeliveryTest {
    @Test
    void rendersBoundedOrderedChunksAndYieldsToAlreadyQueuedUiActions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var turns = new ArrayDeque<Runnable>();
            var observed = new ArrayList<String>();
            var delivery = new EdtEventDelivery<String>(2, events -> observed.add(String.join(",", events)), turns::addLast);
            delivery.submit(List.of("loaded", "row3", "log3", "row1", "log1"));
            turns.addLast(() -> observed.add("UI action"));
            assertTrue(observed.isEmpty());
            turns.removeFirst().run();
            assertEquals(List.of("loaded,row3"), observed);
            turns.removeFirst().run();
            assertEquals(List.of("loaded,row3", "UI action"), observed);
            while (!turns.isEmpty()) { turns.removeFirst().run(); }
            assertEquals(List.of("loaded,row3", "UI action", "log3,row1", "log1"), observed);
        });
    }

    @Test
    void completionRunsOnceAfterEveryQueuedEventIncludingSubmissionsBeforeTheBarrierSettles() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var turns = new ArrayDeque<Runnable>();
            var observed = new ArrayList<Integer>();
            var delivery = new EdtEventDelivery<Integer>(2, observed::addAll, turns::addLast);
            delivery.submit(List.of(1, 2, 3));
            delivery.finish(() -> observed.add(99));
            delivery.submit(List.of(4));
            assertTrue(observed.isEmpty());
            turns.removeFirst().run();
            assertEquals(List.of(1, 2), observed);
            turns.removeFirst().run();
            assertEquals(List.of(1, 2, 3, 4, 99), observed);
            assertTrue(turns.isEmpty());
            assertThrows(IllegalStateException.class, () -> delivery.submit(List.of(5)));
            assertThrows(IllegalStateException.class, () -> delivery.finish(() -> { }));
        });
    }

    @Test
    void submissionCopiesItsInputAndRenderChunksAreImmutable() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var turns = new ArrayDeque<Runnable>();
            var observed = new ArrayList<Integer>();
            var delivery = new EdtEventDelivery<Integer>(2, events -> {
                observed.addAll(events);
                assertThrows(UnsupportedOperationException.class, () -> events.add(9));
            }, turns::addLast);
            var events = new ArrayList<>(List.of(1, 2, 3));
            delivery.submit(events);
            events.clear();
            while (!turns.isEmpty()) { turns.removeFirst().run(); }
            assertEquals(List.of(1, 2, 3), observed);
        });
    }

    @Test
    void reentrantSubmissionsKeepFifoOrderAndDoNotDuplicateScheduledTurns() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var turns = new ArrayDeque<Runnable>();
            var observed = new ArrayList<Integer>();
            var reference = new AtomicReference<EdtEventDelivery<Integer>>();
            var delivery = new EdtEventDelivery<Integer>(2, events -> {
                observed.addAll(events);
                if (events.contains(1)) { reference.get().submit(List.of(4)); }
            }, turns::addLast);
            reference.set(delivery);
            delivery.submit(List.of(1, 2, 3));
            delivery.finish(() -> observed.add(99));
            assertEquals(1, turns.size());
            turns.removeFirst().run();
            assertEquals(1, turns.size());
            turns.removeFirst().run();
            assertEquals(List.of(1, 2, 3, 4, 99), observed);
            assertTrue(turns.isEmpty());
        });
    }

    @Test
    void emptyCompletionIsDeferredAndCannotBeRequestedTwice() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var turns = new ArrayDeque<Runnable>();
            var completed = new ArrayList<String>();
            var delivery = new EdtEventDelivery<String>(2, events -> { throw new AssertionError("No events"); }, turns::addLast);
            delivery.submit(List.of());
            assertTrue(turns.isEmpty());
            delivery.finish(() -> completed.add("done"));
            assertTrue(completed.isEmpty());
            assertThrows(IllegalStateException.class, () -> delivery.finish(() -> { }));
            turns.removeFirst().run();
            assertEquals(List.of("done"), completed);
        });
    }

    @Test
    void deliveryRejectsNonEdtMutation() throws Exception {
        var reference = new AtomicReference<EdtEventDelivery<Integer>>();
        SwingUtilities.invokeAndWait(() -> reference.set(new EdtEventDelivery<>(events -> { })));
        assertFalse(SwingUtilities.isEventDispatchThread());
        assertThrows(IllegalStateException.class, () -> reference.get().submit(List.of(1)));
        assertThrows(IllegalStateException.class, () -> reference.get().finish(() -> { }));
    }

    @Test
    void theProductionLimitSplitsABurstAcrossEdtTurns() throws Exception {
        var done = new CountDownLatch(1);
        var sizes = new ArrayList<Integer>();
        SwingUtilities.invokeAndWait(() -> {
            var delivery = new EdtEventDelivery<Integer>(events -> sizes.add(events.size()));
            delivery.submit(IntStream.range(0, 129).boxed().toList());
            delivery.finish(done::countDown);
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> assertEquals(List.of(128, 1), sizes));
    }

    @Test
    void aRenderingFailurePropagatesButDoesNotStrandLaterDeliveryOrCompletion() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var turns = new ArrayDeque<Runnable>();
            var observed = new ArrayList<Integer>();
            var delivery = new EdtEventDelivery<Integer>(1, events -> {
                if (events.contains(1)) { throw new IllegalStateException("Fixture rendering failure"); }
                observed.addAll(events);
            }, turns::addLast);
            delivery.submit(List.of(1, 2));
            delivery.finish(() -> observed.add(99));
            assertThrows(IllegalStateException.class, () -> turns.removeFirst().run());
            assertEquals(1, turns.size());
            turns.removeFirst().run();
            assertEquals(List.of(2, 99), observed);
        });
    }
}
