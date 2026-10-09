package com.ivanocj.cameras.availability.application.service;

import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.port.ProbeRunner;
import java.io.IOException;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Explicit protocol dispatch: Yoosee server names or numeric IDs never imply PPCS wire compatibility. */
public final class ProtocolProbeRouter implements ProbeRunner {
    private final ProbeRunner legacy;
    private final ProbeRunner gwell;
    private final ProbeRunner iotvideo;

    public ProtocolProbeRouter(ProbeRunner legacy, ProbeRunner gwell, ProbeRunner iotvideo) {
        this.legacy = Objects.requireNonNull(legacy);
        this.gwell = Objects.requireNonNull(gwell);
        this.iotvideo = Objects.requireNonNull(iotvideo);
    }

    @Override
    public ProbeResult check(ProbeRequest request, BooleanSupplier cancelled, Consumer<String> progress) throws IOException {
        var backend = switch (request.protocol()) {
            case PPCS, XQ -> legacy;
            case GWELL -> gwell;
            case IOTVIDEO -> iotvideo;
        };
        return backend.check(request, cancelled, progress);
    }
}
