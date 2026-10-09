package com.ivanocj.cameras.availability.application.model;

import com.ivanocj.cameras.availability.domain.model.DeviceId;
import com.ivanocj.cameras.availability.domain.model.DeviceIdentifier;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;

public record ProbeRequest(DeviceIdentifier identifier, List<InetSocketAddress> servers, Duration timeout, boolean lanSearch,
                           TransportProtocol protocol) {
    public ProbeRequest {
        protocol.validate(identifier);
        servers = List.copyOf(servers);
    }

    public ProbeRequest(DeviceId did, List<InetSocketAddress> servers, Duration timeout, boolean lanSearch) {
        this(did, servers, timeout, lanSearch, TransportProtocol.PPCS);
    }

    /** Source-compatible legacy accessor. General callers use {@link #identifier()} instead. */
    public DeviceId did() {
        if (identifier instanceof DeviceId did) {
            return did;
        }
        throw new IllegalStateException("This request has a " + protocol + " identifier, not a PPCS/XQ DID.");
    }
}
