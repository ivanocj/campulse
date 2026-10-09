package com.ivanocj.cameras.availability.infrastructure.logging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.status.ErrorStatus;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class LoggingSafetyTest {
    @Test
    void asyncOverloadMayDiscardDebugButNeverOperationalInfoWarningsOrErrors() {
        var appender = new OperationalAsyncAppender();
        for (var level : new Level[] {Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR}) {
            var event = new LoggingEvent();
            event.setLevel(level);
            assertEquals(level.toInt() <= Level.DEBUG_INT, appender.isDiscardable(event));
        }
        assertFalse(appender.isNeverBlock());
        appender.prepareForShutdown();
        assertTrue(appender.isNeverBlock());
    }

    @Test
    void exceptionOutputIsBoundedAndDoesNotFollowCyclicCausesOrRevealMessages() {
        var first = new IOException("secret 1");
        var second = new IOException("secret 2");
        first.initCause(second);
        second.initCause(first);
        var event = new LoggingEvent("", new LoggerContext().getLogger("test"), Level.ERROR, "event=failure", first, null);
        var formatted = new SafeThrowableConverter().convert(event);
        assertTrue(formatted.contains("java.io.IOException"));
        assertTrue(formatted.length() < 20000);
        assertFalse(formatted.contains("secret"));
    }

    @Test
    void backendErrorsUseADirectRateLimitedSafeConsolePathInsteadOfRecursingThroughTheFileAppender() {
        var context = new LoggerContext();
        var console = new ListAppender<ILoggingEvent>();
        console.start();
        var listener = new SafeLoggingStatusListener(context, console);
        for (int index = 0; index < 50; index++) {
            listener.addStatusEvent(new ErrorStatus("sensitive path and description", this));
        }
        assertEquals(1, console.list.size());
        assertTrue(console.list.getFirst().getFormattedMessage().contains("event=logging_backend_failure"));
        assertFalse(console.list.getFirst().getFormattedMessage().contains("sensitive"));
        console.stop();
        context.stop();
    }
}
