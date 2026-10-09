package com.ivanocj.cameras.availability.infrastructure.logging;

import ch.qos.logback.classic.pattern.ThrowableHandlingConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Retains bounded exception types/frames/causes without persisting messages that can contain device data. */
public final class SafeThrowableConverter extends ThrowableHandlingConverter {
    private static final int MAX_THROWABLES = 16;
    private static final int MAX_FRAMES = 64;

    @Override
    public String convert(ILoggingEvent event) {
        var output = new StringBuilder();
        Set<IThrowableProxy> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        append(output, event.getThrowableProxy(), "", visited, new int[] {MAX_FRAMES});
        return output.toString();
    }

    private static void append(StringBuilder output, IThrowableProxy failure, String prefix,
                               Set<IThrowableProxy> visited, int[] remainingFrames) {
        if (failure == null) { return; }
        if (visited.size() >= MAX_THROWABLES || !visited.add(failure) || failure.isCyclic()) {
            output.append(prefix).append("[exception chain truncated]\n");
            return;
        }
        output.append(prefix).append(failure.getClassName()).append(": [message omitted]\n");
        var frames = failure.getStackTraceElementProxyArray();
        if (frames != null) {
            for (var frame : frames) {
                if (remainingFrames[0]-- <= 0) {
                    output.append("\t[stack frames truncated]\n");
                    break;
                }
                output.append("\tat ").append(frame.getStackTraceElement().toString()
                        .replace('\r', ' ').replace('\n', ' ')).append('\n');
            }
        }
        var suppressed = failure.getSuppressed();
        if (suppressed != null) {
            for (int index = 0; index < Math.min(suppressed.length, MAX_THROWABLES); index++) {
                append(output, suppressed[index], "Suppressed: ", visited, remainingFrames);
            }
        }
        append(output, failure.getCause(), "Caused by: ", visited, remainingFrames);
    }
}
