package com.ivanocj.cameras.availability.infrastructure.probe;

import static com.ivanocj.cameras.availability.infrastructure.protocol.MessageType.ALIVE;
import static com.ivanocj.cameras.availability.infrastructure.protocol.MessageType.ALIVE_ACK;
import static com.ivanocj.cameras.availability.infrastructure.protocol.MessageType.CLOSE;
import static com.ivanocj.cameras.availability.infrastructure.protocol.MessageType.HELLO;
import static com.ivanocj.cameras.availability.infrastructure.protocol.MessageType.LAN_SEARCH;
import static com.ivanocj.cameras.availability.infrastructure.protocol.MessageType.P2P_READY;
import static com.ivanocj.cameras.availability.infrastructure.protocol.MessageType.PUNCH_PACKET;
import static com.ivanocj.cameras.availability.infrastructure.protocol.MessageType.RELAY_HELLO;
import static com.ivanocj.cameras.availability.infrastructure.protocol.MessageType.RELAY_LIST_REQUEST;
import static com.ivanocj.cameras.availability.infrastructure.protocol.MessageType.RELAY_PORT_REQUEST;
import static com.ivanocj.cameras.availability.infrastructure.protocol.MessageType.RELAY_READY;

import com.ivanocj.cameras.availability.application.model.P2pInformation;
import com.ivanocj.cameras.availability.application.logging.LogContext;
import com.ivanocj.cameras.availability.application.model.P2pInformation.ConnectionMode;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.model.SessionRequest;
import com.ivanocj.cameras.availability.application.port.DatagramTransport;
import com.ivanocj.cameras.availability.application.port.SessionProbe;
import com.ivanocj.cameras.availability.infrastructure.protocol.PpcsMessages;
import com.ivanocj.cameras.availability.infrastructure.protocol.PpcsPacket;
import com.ivanocj.cameras.availability.infrastructure.protocol.ProtocolMessages;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The shared SY/XQ IPv4 UDP availability sequence: rendezvous, direct/LAN punching, UDP relay, alive check. */
public final class UdpSessionProbe implements SessionProbe {
    private static final Logger LOG = LoggerFactory.getLogger(UdpSessionProbe.class);
    private static final long RETRANSMIT_NANOS = Duration.ofMillis(500).toNanos();
    private static final long RECEIVE_SLICE_NANOS = Duration.ofMillis(100).toNanos();
    private static final InetSocketAddress LAN_BROADCAST = new InetSocketAddress("255.255.255.255", 32108);

    private final DatagramTransport transport;
    private final LongSupplier nanoTime;

    public UdpSessionProbe(DatagramTransport transport, LongSupplier nanoTime) {
        this.transport = transport;
        this.nanoTime = nanoTime;
    }

    @Override
    public ProbeResult check(SessionRequest request, BooleanSupplier cancelled, Consumer<String> progress)
            throws IOException {
        try (var operation = LogContext.probe()) {
            return new Session(request, cancelled, progress).run();
        }
    }

    /** State for one check. A DID-matched peer, once selected, is the session's single owner. */
    private final class Session {
        private final SessionRequest request;
        private final BooleanSupplier cancelled;
        private final Consumer<String> progress;
        private final ProtocolMessages messages;
        private final byte[] identity;
        private final long started;
        private final long deadline;
        private final long relayStart;
        private final Set<InetSocketAddress> candidates = new LinkedHashSet<>();
        private final Set<InetSocketAddress> lanCandidates = new HashSet<>();
        private final Set<InetSocketAddress> relays = new LinkedHashSet<>();
        private final Set<InetSocketAddress> relayPeers = new HashSet<>();
        private final Set<InetSocketAddress> relayEndpoints = new LinkedHashSet<>();
        private final Set<InetSocketAddress> respondingServers = new LinkedHashSet<>();
        private final Map<InetSocketAddress, Integer> serverStatuses = new LinkedHashMap<>();
        private final Map<InetSocketAddress, Integer> rejections = new HashMap<>();
        private final Map<InetSocketAddress, PpcsPacket> relayRetries = new HashMap<>();
        private final Set<InetSocketAddress> unreachable = new HashSet<>();
        private IOException lastSendFailure;
        private boolean serverResponded;
        private InetSocketAddress peer;
        private ConnectionMode mode = ConnectionMode.NONE;
        private InetSocketAddress publicEndpoint;
        private int malformedPackets;
        private int sendFailures;

        Session(SessionRequest request, BooleanSupplier cancelled, Consumer<String> progress) {
            this.request = request;
            this.cancelled = cancelled;
            this.progress = progress;
            messages = new ProtocolMessages(request.protocol());
            identity = messages.identity(request.did());
            started = nanoTime.getAsLong();
            deadline = started + request.timeout().toNanos();
            relayStart = deadline - request.timeout().toNanos() / 2;
        }

        ProbeResult run() throws IOException {
            LOG.debug("event=udp_session_started protocol={} server_count={} lan_search={}",
                    request.protocol(), request.servers().size(), request.lanSearch());
            long nextSend = nanoTime.getAsLong();
            progress.accept(request.servers().isEmpty() ? "Checking the local network without servers."
                    : "Checking " + request.protocol() + " servers.");
            progress.accept("Connecting by DID: " + request.did().value());
            try {
                while (nanoTime.getAsLong() < deadline) {
                    if (cancelled.getAsBoolean()) {
                        return cancelledResult();
                    }
                    if (nanoTime.getAsLong() >= nextSend) {
                        sendRound();
                        nextSend = nanoTime.getAsLong() + RETRANSMIT_NANOS;
                    }
                    var received = transport.receive(
                            Duration.ofNanos(Math.min(RECEIVE_SLICE_NANOS, deadline - nanoTime.getAsLong())));
                    if (cancelled.getAsBoolean()) {
                        return cancelledResult();
                    }
                    if (nanoTime.getAsLong() >= deadline) {
                        break;
                    }
                    if (received.isPresent()) {
                        var confirmed = handle(received.get());
                        if (confirmed.isPresent()) {
                            return confirmed.get();
                        }
                    }
                }
                return expiredResult();
            } finally {
                if (peer != null) {
                    send(PpcsPacket.empty(CLOSE), peer);
                }
                LOG.debug("event=udp_session_closed mode={} malformed_packets={} send_failures={} "
                                + "responding_servers={} device_candidates={} relay_candidates={}",
                        mode, malformedPackets, sendFailures, respondingServers.size(), candidates.size(), relayEndpoints.size());
            }
        }

        /** (Re)transmits every outstanding request. Fails only when no datagram at all could be sent. */
        private void sendRound() throws IOException {
            boolean delivered = false;
            for (var server : request.servers()) {
                delivered |= send(PpcsPacket.empty(HELLO), server);
                delivered |= send(messages.connectRequest(request.did(), transport.localAddress()), server);
                if (nanoTime.getAsLong() >= relayStart) {
                    delivered |= send(new PpcsPacket(RELAY_LIST_REQUEST, identity), server);
                }
            }
            if (request.lanSearch()) {
                delivered |= send(PpcsPacket.empty(LAN_SEARCH), LAN_BROADCAST);
            }
            if (peer != null) {
                delivered |= send(PpcsPacket.empty(ALIVE), peer);
            } else {
                for (var candidate : candidates) {
                    delivered |= send(new PpcsPacket(PUNCH_PACKET, identity), candidate);
                }
            }
            for (var retry : relayRetries.entrySet()) {
                delivered |= send(retry.getValue(), retry.getKey());
            }
            if (!delivered) {
                throw new IOException("Could not send to any server: " + describe(lastSendFailure), lastSendFailure);
            }
        }

        /** Sends one datagram; a failure for one destination is logged once and never ends the check. */
        private boolean send(PpcsPacket packet, InetSocketAddress destination) {
            try {
                transport.send(packet.encode(), destination);
                unreachable.remove(destination);
                return true;
            } catch (IOException failed) {
                sendFailures++;
                lastSendFailure = failed;
                if (unreachable.add(destination)) {
                    LOG.warn("event=udp_send_failed packet_type={} cleanup={}", packet.type(), packet.type() == CLOSE.code(), failed);
                    progress.accept("Could not send to " + destination + ": " + describe(failed));
                }
                return false;
            }
        }

        private Optional<ProbeResult> handle(DatagramTransport.Received datagram) {
            try {
                var packet = PpcsPacket.decode(datagram.bytes());
                var type = packet.messageType();
                if (type.isEmpty()) {
                    return Optional.empty();
                }
                var source = datagram.source();
                byte[] payload = packet.payload();
                switch (type.get()) {
                    case HELLO_ACK -> onHelloAck(source, payload);
                    case P2P_REQUEST_ACK -> onP2pRequestAck(source, payload);
                    case PUNCH_TO -> onPunchTo(source, payload);
                    case PUNCH_PACKET -> onPunchPacket(source, payload);
                    case P2P_READY -> onReady(source, payload, candidates, false);
                    case RELAY_LIST -> onRelayList(source, payload);
                    case RELAY_HELLO_ACK -> onRelayHelloAck(source, payload);
                    case RELAY_PORT -> onRelayPort(source, payload);
                    case RELAY_TO -> onRelayTo(source, payload);
                    case RELAY_READY -> onReady(source, payload, relayPeers, true);
                    case ALIVE -> onAlive(source, payload);
                    case ALIVE_ACK -> {
                        return onAliveAck(source, payload);
                    }
                    default -> {
                    }
                }
            } catch (IllegalArgumentException malformed) {
                malformedPackets++;
                // Malformed or spoofed input is unconfirmed evidence, not a failed check.
            }
            return Optional.empty();
        }

        private void onHelloAck(InetSocketAddress source, byte[] payload) {
            if (payload.length == 16 && isServer(source)) {
                publicEndpoint = PpcsMessages.readAddress(payload, 0);
                markServerResponse(source);
                progress.accept(request.protocol() + " server replied: " + source);
            }
        }

        private void onP2pRequestAck(InetSocketAddress source, byte[] payload) {
            if (payload.length != 4 || !isServer(source)) {
                return;
            }
            markServerResponse(source);
            int wireStatus = payload[0];
            var previous = serverStatuses.put(source, wireStatus);
            if (!Integer.valueOf(wireStatus).equals(previous)) {
                LOG.debug("event=server_lookup_status status_unsigned={}", Byte.toUnsignedInt(payload[0]));
            }
            if (wireStatus != 0) {
                rejections.put(source, wireStatus);
                progress.accept(request.protocol() + " server " + source + " returned wire status " + wireStatus
                        + " (unsigned " + Byte.toUnsignedInt(payload[0]) + ").");
            } else {
                rejections.remove(source);
            }
        }

        private void onPunchTo(InetSocketAddress source, byte[] payload) {
            if (payload.length == 16 && isServer(source)) {
                var candidate = PpcsMessages.readAddress(payload, 0);
                markServerResponse(source);
                candidates.add(candidate);
                send(new PpcsPacket(PUNCH_PACKET, identity), candidate);
            }
        }

        private void onPunchPacket(InetSocketAddress source, byte[] payload) {
            boolean lanDevice = request.lanSearch()
                    && (source.getAddress().isSiteLocalAddress() || source.getAddress().isLoopbackAddress());
            if (messages.matchesPeerIdentity(PUNCH_PACKET, payload, identity) && (candidates.contains(source) || lanDevice)) {
                if (!candidates.contains(source)) {
                    lanCandidates.add(source);
                }
                candidates.add(source);
                send(new PpcsPacket(PUNCH_PACKET, identity), source);
            }
        }

        private void onReady(InetSocketAddress source, byte[] payload, Set<InetSocketAddress> expected, boolean relay) {
            if (peer == null && expected.contains(source)
                    && messages.matchesPeerIdentity(relay ? RELAY_READY : P2P_READY, payload, identity)) {
                peer = source;
                mode = relay ? ConnectionMode.UDP_RELAY
                        : lanCandidates.contains(source) ? ConnectionMode.LAN : ConnectionMode.DIRECT_P2P;
                LOG.debug("event=session_selected connection_mode={}", mode);
                relayRetries.remove(peer);
                progress.accept("DID-matched peer ready" + (relay ? " via UDP relay: " : ": ") + peer);
                send(PpcsPacket.empty(ALIVE), peer);
            }
        }

        private void onRelayList(InetSocketAddress source, byte[] payload) {
            if (payload.length < 4 || !isServer(source)) {
                return;
            }
            int count = Byte.toUnsignedInt(payload[0]);
            if (payload.length != 4 + count * 16) {
                return;
            }
            // Decode the entire list before accepting evidence or sending to any of its endpoints.
            var addresses = new ArrayList<InetSocketAddress>(count);
            for (int i = 0; i < count; i++) {
                addresses.add(PpcsMessages.readAddress(payload, 4 + i * 16));
            }
            markServerResponse(source);
            for (var relay : addresses) {
                relays.add(relay);
                relayEndpoints.add(relay);
                var hello = PpcsPacket.empty(RELAY_HELLO);
                relayRetries.putIfAbsent(relay, hello);
                send(hello, relay);
            }
        }

        private void onRelayHelloAck(InetSocketAddress source, byte[] payload) {
            if (payload.length == 0 && relays.contains(source)) {
                var portRequest = PpcsPacket.empty(RELAY_PORT_REQUEST);
                relayRetries.put(source, portRequest);
                send(portRequest, source);
            }
        }

        private void onRelayPort(InetSocketAddress source, byte[] payload) {
            if (payload.length != 8 || !relays.contains(source)) {
                return;
            }
            var buffer = ByteBuffer.wrap(payload);
            int cookie = buffer.getInt();
            var relay = new InetSocketAddress(source.getAddress(), Short.toUnsignedInt(buffer.getShort()));
            relayEndpoints.add(relay);
            relayRetries.remove(source);
            for (var server : request.servers()) {
                var relayRequest = messages.relayRequest(request.did(), relay, cookie);
                relayRetries.put(server, relayRequest);
                send(relayRequest, server);
            }
        }

        private void onRelayTo(InetSocketAddress source, byte[] payload) {
            if (payload.length != 20 || !isServer(source)) {
                return;
            }
            var relayPeer = PpcsMessages.readAddress(payload, 0);
            markServerResponse(source);
            relayPeers.add(relayPeer);
            relayEndpoints.add(relayPeer);
            var punch = messages.relayPunch(request.did(), ByteBuffer.wrap(payload).getInt(16));
            relayRetries.remove(source);
            relayRetries.put(relayPeer, punch);
            send(punch, relayPeer);
        }

        private void onAlive(InetSocketAddress source, byte[] payload) {
            if (payload.length == 0 && source.equals(peer)) {
                send(PpcsPacket.empty(ALIVE_ACK), peer);
            }
        }

        private Optional<ProbeResult> onAliveAck(InetSocketAddress source, byte[] payload) {
            if (payload.length != 0 || !source.equals(peer)) {
                return Optional.empty();
            }
            boolean relay = mode == ConnectionMode.UDP_RELAY;
            progress.accept(relay ? "Confirmed UDP relay reachability." : "Confirmed direct P2P reachability.");
            return Optional.of(result(relay ? ProbeResult.Status.REACHABLE_RELAY : ProbeResult.Status.REACHABLE_P2P,
                    "DID-matched peer confirmed by alive acknowledgement."));
        }

        private boolean isServer(InetSocketAddress source) {
            return request.servers().contains(source);
        }

        private void markServerResponse(InetSocketAddress source) {
            serverResponded = true;
            if (respondingServers.add(source)) {
                LOG.debug("event=server_responded server_index={}", request.servers().indexOf(source) + 1);
            }
        }

        private ProbeResult cancelledResult() {
            return result(ProbeResult.Status.CANCELLED, "Check cancelled.");
        }

        private ProbeResult expiredResult() {
            if (!request.servers().isEmpty() && rejections.keySet().containsAll(request.servers())) {
                return result(ProbeResult.Status.SERVER_REJECTED, request.protocol() + " DID lookup rejected: "
                        + rejections + ". Device reachability is not confirmed.");
            }
            return serverResponded
                    ? result(ProbeResult.Status.TIMED_OUT, request.protocol()
                            + " server responded, but no device handshake completed before the deadline.")
                    : result(ProbeResult.Status.INCONCLUSIVE, request.servers().isEmpty()
                            ? "No matching LAN peer completed an alive-confirmed handshake. Device availability is unknown."
                            : "No " + request.protocol() + " server or matching peer responded. Device availability is unknown.");
        }

        private ProbeResult result(ProbeResult.Status status, String detail) {
            LOG.debug("event=udp_session_result status={} connection_mode={}", status, mode);
            var information = new P2pInformation(Optional.of(request.protocol()), mode,
                    Optional.of(transport.localAddress()), request.servers(), List.copyOf(respondingServers),
                    List.copyOf(candidates), List.copyOf(relayEndpoints), serverStatuses);
            return new ProbeResult(status, detail, serverResponded, Optional.ofNullable(peer),
                    Optional.ofNullable(publicEndpoint), Duration.ofNanos(nanoTime.getAsLong() - started), information);
        }
    }

    private static String describe(Throwable failure) {
        if (failure == null) {
            return "unknown error";
        }
        return failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
    }
}
