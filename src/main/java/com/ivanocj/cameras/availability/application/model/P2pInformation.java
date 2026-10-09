package com.ivanocj.cameras.availability.application.model;

import com.ivanocj.cameras.availability.domain.model.TransportProtocol;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable network evidence from one probe, including replies received before timeout/cancellation.
 * Device candidates are not proof of reachability; relay endpoints are infrastructure, not device IPs.
 * The mode describes the selected DID-matched session, whose alive confirmation is in {@link ProbeResult#status()}.
 * Server statuses are the last raw signed acknowledgement byte from each server, not native SDK error codes.
 */
public record P2pInformation(Optional<TransportProtocol> protocol, ConnectionMode mode,
                             Optional<InetSocketAddress> localEndpoint,
                             List<InetSocketAddress> resolvedServers, List<InetSocketAddress> respondingServers,
                             List<InetSocketAddress> deviceCandidates, List<InetSocketAddress> relayCandidates,
                             Map<InetSocketAddress, Integer> serverStatuses) {
    public P2pInformation {
        resolvedServers = List.copyOf(resolvedServers);
        respondingServers = List.copyOf(respondingServers);
        deviceCandidates = List.copyOf(deviceCandidates);
        relayCandidates = List.copyOf(relayCandidates);
        serverStatuses = Collections.unmodifiableMap(new LinkedHashMap<>(serverStatuses));
    }

    public static P2pInformation empty() {
        return new P2pInformation(Optional.empty(), ConnectionMode.NONE, Optional.empty(),
                List.of(), List.of(), List.of(), List.of(), Map.of());
    }

    public enum ConnectionMode { NONE, LAN, DIRECT_P2P, UDP_RELAY }
}
