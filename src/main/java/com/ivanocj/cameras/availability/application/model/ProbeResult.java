package com.ivanocj.cameras.availability.application.model;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Terminal probe evidence. Peer is the DID-matched session endpoint (possibly a relay, not the device IP).
 * Public endpoint is the client's server-observed mapping, not the camera's IP.
 */
public record ProbeResult(Status status, String detail, boolean serverResponded,
                          Optional<InetSocketAddress> peer, Optional<InetSocketAddress> publicEndpoint,
                          Duration elapsed, P2pInformation p2pInformation, List<GwellLanDevice> lanDevices,
                          Optional<RemotePresence> remotePresence) {
    public ProbeResult {
        lanDevices = List.copyOf(lanDevices);
        java.util.Objects.requireNonNull(remotePresence);
    }

    public ProbeResult(Status status, String detail, boolean serverResponded,
                       Optional<InetSocketAddress> peer, Optional<InetSocketAddress> publicEndpoint,
                       Duration elapsed, P2pInformation p2pInformation, List<GwellLanDevice> lanDevices) {
        this(status, detail, serverResponded, peer, publicEndpoint, elapsed, p2pInformation, lanDevices, Optional.empty());
    }

    public ProbeResult(Status status, String detail, boolean serverResponded,
                       Optional<InetSocketAddress> peer, Optional<InetSocketAddress> publicEndpoint,
                       Duration elapsed, P2pInformation p2pInformation) {
        this(status, detail, serverResponded, peer, publicEndpoint, elapsed, p2pInformation, List.of());
    }

    public ProbeResult(Status status, String detail, boolean serverResponded,
                       Optional<InetSocketAddress> peer, Optional<InetSocketAddress> publicEndpoint, Duration elapsed) {
        this(status, detail, serverResponded, peer, publicEndpoint, elapsed, P2pInformation.empty());
    }

    public ProbeResult(Status status) {
        this(status, "", false, Optional.empty(), Optional.empty(), Duration.ZERO);
    }

    public enum Status {
        INCONCLUSIVE, TIMED_OUT, REACHABLE_P2P, REACHABLE_RELAY, CANCELLED, SERVER_REJECTED,
        DISCOVERED_LAN, UNSUPPORTED, REPORTED_ONLINE, REPORTED_OFFLINE, AUTH_REQUIRED, AUTH_FAILED
    }
}
