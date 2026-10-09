package com.ivanocj.cameras.availability.testsupport;

import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.port.SessionProbeFactory;

public final class TestSessionProbes {
    public static final SessionProbeFactory NO_REPLY = (transport, clock) -> (request, cancelled, progress) -> {
        if (cancelled.getAsBoolean()) {
            return new ProbeResult(ProbeResult.Status.CANCELLED);
        }
        transport.receive(request.timeout());
        return new ProbeResult(ProbeResult.Status.INCONCLUSIVE);
    };

    private TestSessionProbes() {
    }
}
