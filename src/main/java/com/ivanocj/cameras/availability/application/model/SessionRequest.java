package com.ivanocj.cameras.availability.application.model;

import com.ivanocj.cameras.availability.domain.model.DeviceId;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;

import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * A session probe's input after server resolution: distinct, resolved IPv4 endpoints and the time left
 * on the overall deadline. LAN-only sessions need no server; host names are never passed to the socket.
 */
public record SessionRequest(DeviceId did, List<InetSocketAddress> servers, Duration timeout, boolean lanSearch,
                             TransportProtocol protocol) {
    public SessionRequest {
        protocol.validate(did);
        servers = List.copyOf(new LinkedHashSet<>(servers));
        if ((servers.isEmpty() && !lanSearch)
                || servers.stream().anyMatch(server -> !(server.getAddress() instanceof Inet4Address))) {
            throw new IllegalArgumentException("A session needs resolved IPv4 servers or LAN search enabled.");
        }
    }

    public SessionRequest(DeviceId did, List<InetSocketAddress> servers, Duration timeout, boolean lanSearch) {
        this(did, servers, timeout, lanSearch, TransportProtocol.PPCS);
    }
}
