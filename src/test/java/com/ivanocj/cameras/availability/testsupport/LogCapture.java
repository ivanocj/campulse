package com.ivanocj.cameras.availability.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/** Captures only application events without creating files or changing the production logging lifecycle. */
public final class LogCapture implements AutoCloseable {
    private final Logger logger = (Logger) LoggerFactory.getLogger("com.ivanocj.cameras.availability");
    private final Level previousLevel = logger.getLevel();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    public LogCapture() {
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
    }

    public List<ILoggingEvent> events() {
        synchronized (appender) { return List.copyOf(appender.list); }
    }

    public List<ILoggingEvent> events(String event) {
        return events().stream().filter(line -> line.getFormattedMessage().contains("event=" + event)).toList();
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        logger.setLevel(previousLevel);
        appender.stop();
    }
}
