package com.ivanocj.cameras.availability.application.port;

import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@FunctionalInterface
public interface ProbeRunner {
    /** Each invocation owns its socket/session. Parallel batch use requires a thread-safe implementation. */
    ProbeResult check(ProbeRequest request, BooleanSupplier cancelled, Consumer<String> progress) throws IOException;
}
