package com.ivanocj.cameras.availability.application.model;

import com.ivanocj.cameras.availability.domain.model.TransportProtocol;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decoded server endpoints. {@code protocol} is present when the configuration's encoding identifies its
 * transport (SY or XQ); a plain host list leaves the choice to the user.
 */
public record ServerConfiguration(List<InetSocketAddress> servers, Optional<TransportProtocol> protocol) {
    public ServerConfiguration {
        servers = List.copyOf(servers);
        Objects.requireNonNull(protocol);
    }

    public ServerConfiguration(List<InetSocketAddress> servers) {
        this(servers, Optional.empty());
    }

    public ServerConfiguration withProtocol(TransportProtocol protocol) {
        return new ServerConfiguration(servers, Optional.of(protocol));
    }
}
