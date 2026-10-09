package com.ivanocj.cameras.availability.application.logging;

import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

/** Scoped, opaque operation IDs. Context must be explicitly carried across worker/virtual-thread boundaries. */
public final class LogContext implements AutoCloseable {
    private final Map<String, String> previous;

    private LogContext(Map<String, String> context) {
        previous = MDC.getCopyOfContextMap();
        MDC.setContextMap(context);
    }

    public static LogContext batch() { return operation("batchId"); }

    public static LogContext probe() { return operation("probeId"); }

    public static LogContext probe(int sourceLine) {
        var scope = probe();
        MDC.put("sourceLine", Integer.toString(sourceLine));
        return scope;
    }

    private static LogContext operation(String key) {
        var scope = new LogContext(snapshot());
        if (MDC.get(key) == null) {
            MDC.put(key, UUID.randomUUID().toString());
        }
        return scope;
    }

    public static Map<String, String> snapshot() {
        var current = MDC.getCopyOfContextMap();
        return current == null ? Map.of() : Map.copyOf(current);
    }

    public static LogContext inherit(Map<String, String> context) { return new LogContext(context); }

    @Override
    public void close() {
        if (previous == null) { MDC.clear(); }
        else { MDC.setContextMap(previous); }
    }
}
