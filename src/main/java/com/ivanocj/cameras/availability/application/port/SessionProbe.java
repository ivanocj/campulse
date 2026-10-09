package com.ivanocj.cameras.availability.application.port;

import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.model.SessionRequest;
import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@FunctionalInterface
public interface SessionProbe {
    ProbeResult check(SessionRequest request, BooleanSupplier cancelled, Consumer<String> progress) throws IOException;
}
