package com.ivanocj.cameras.availability.infrastructure.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.status.StatusListener;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Reports backend failures directly to the console, never recursively to the failing file appender. */
final class SafeLoggingStatusListener implements StatusListener {
    private final LoggerContext context;
    private final Appender<ILoggingEvent> console;
    private final AtomicLong nextReport = new AtomicLong(Long.MIN_VALUE);

    SafeLoggingStatusListener(LoggerContext context, Appender<ILoggingEvent> console) {
        this.context = context;
        this.console = console;
    }

    @Override
    public void addStatusEvent(Status status) {
        if (status.getLevel() < Status.ERROR) { return; }
        long now = System.nanoTime();
        long next = nextReport.get();
        if (now < next || !nextReport.compareAndSet(next, now + TimeUnit.SECONDS.toNanos(10))) { return; }
        var origin = status.getOrigin();
        var event = new LoggingEvent(SafeLoggingStatusListener.class.getName(),
                context.getLogger(ApplicationLogging.class), Level.ERROR,
                "event=logging_backend_failure component=" + (origin == null ? "unknown" : origin.getClass().getSimpleName()),
                status.getThrowable(), null);
        console.doAppend(event);
    }
}
