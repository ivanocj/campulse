package com.ivanocj.cameras.availability.application.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LogContextTest {
    @AfterEach
    void clearContext() { MDC.clear(); }

    @Test
    void scopesCreateOpaqueIdsAndRestoreTheCallersContextEvenAfterAFailure() {
        MDC.put("caller", "retained");
        try (var batch = LogContext.batch()) {
            var batchId = UUID.fromString(MDC.get("batchId")).toString();
            try (var nestedBatch = LogContext.batch(); var probe = LogContext.probe()) {
                assertEquals(batchId, MDC.get("batchId"));
                var probeId = UUID.fromString(MDC.get("probeId")).toString();
                try (var nestedProbe = LogContext.probe()) {
                    assertEquals(probeId, MDC.get("probeId"));
                }
            }
            assertNull(MDC.get("probeId"));
            String first;
            try (var probe = LogContext.probe()) { first = MDC.get("probeId"); }
            try (var probe = LogContext.probe()) { assertNotEquals(first, MDC.get("probeId")); }
        }
        assertEquals(Map.of("caller", "retained"), MDC.getCopyOfContextMap());
        try {
            try (var probe = LogContext.probe()) { throw new IllegalStateException("test failure"); }
        } catch (IllegalStateException expected) {
            assertEquals(Map.of("caller", "retained"), MDC.getCopyOfContextMap());
        }
    }

    @Test
    void inheritedContextIsExplicitForVirtualThreadsAndDoesNotLeakBetweenTasks() throws Exception {
        try (var batch = LogContext.batch(); var probe = LogContext.probe();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var parent = LogContext.snapshot();
            executor.submit(() -> {
                assertNull(MDC.get("batchId"));
                try (var inherited = LogContext.inherit(parent)) {
                    assertEquals(parent, MDC.getCopyOfContextMap());
                }
                assertNull(MDC.get("batchId"));
                assertNull(MDC.get("probeId"));
            }).get(2, TimeUnit.SECONDS);
            assertEquals(parent, MDC.getCopyOfContextMap());
        }
        assertNull(MDC.get("batchId"));
    }
}
