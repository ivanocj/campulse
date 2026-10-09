package com.ivanocj.cameras.availability.infrastructure.logging;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** Keeps operational INFO/WARN/ERROR events; only verbose diagnostics may be dropped under pressure. */
final class OperationalAsyncAppender extends AsyncAppender {
    /** Shutdown must not block trying to enqueue its last record before the bounded flush even begins. */
    void prepareForShutdown() {
        setNeverBlock(true);
    }

    @Override
    protected boolean isDiscardable(ILoggingEvent event) {
        return event.getLevel().toInt() <= Level.DEBUG_INT;
    }
}
