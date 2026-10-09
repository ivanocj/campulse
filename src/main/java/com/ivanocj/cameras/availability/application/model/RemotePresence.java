package com.ivanocj.cameras.availability.application.model;

import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Server-reported presence, never evidence of an alive-confirmed device transport. */
public record RemotePresence(State state, TransportProtocol protocol, Source source,
                             Optional<Instant> reportedAt, Instant receivedAt) {
    public RemotePresence {
        Objects.requireNonNull(state);
        Objects.requireNonNull(protocol);
        Objects.requireNonNull(source);
        Objects.requireNonNull(reportedAt);
        Objects.requireNonNull(receivedAt);
        if (protocol.legacyUdp()) { throw new IllegalArgumentException("Presence requires a Yoosee backend."); }
        if ((protocol == TransportProtocol.GWELL) != (source == Source.GWELL_STATUS_QUERY)) {
            throw new IllegalArgumentException("Remote presence source must match its backend.");
        }
    }

    public enum State { ONLINE, OFFLINE, UNKNOWN, STALE }
    public enum Source { GWELL_STATUS_QUERY, IOTVIDEO_MODEL }
}
