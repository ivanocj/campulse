package com.ivanocj.cameras.availability.infrastructure.probe;

import com.ivanocj.cameras.availability.application.model.SessionRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.model.P2pInformation;
import com.ivanocj.cameras.availability.application.port.DatagramTransport;
import com.ivanocj.cameras.availability.domain.model.DeviceId;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.infrastructure.protocol.PpcsMessages;
import com.ivanocj.cameras.availability.infrastructure.protocol.PpcsPacket;
import com.ivanocj.cameras.availability.testsupport.FakeDatagramTransport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class UdpSessionProbeTest {
    static final DeviceId DID = new DeviceId("FFFF-158409-EBDAC");
    static final InetSocketAddress SERVER = new InetSocketAddress("127.0.0.1", 32100);
    static final InetSocketAddress PEER = new InetSocketAddress("127.0.0.1", 32108);
    final FakeTransport transport = new FakeTransport();

    ProbeResult run() throws IOException {
        return run(false);
    }

    ProbeResult run(boolean lanSearch) throws IOException {
        return new UdpSessionProbe(transport, () -> transport.nanos).check(
                new SessionRequest(DID, List.of(SERVER), Duration.ofSeconds(1), lanSearch),
                () -> false, message -> { });
    }

    @Test
    void startsWithHelloAndADidSpecificConnectionRequest() throws IOException {
        run();
        assertEquals(List.of(0x00, 0x20), transport.sent.stream().limit(2).map(s -> s.packet.type()).toList());
    }

    @Test
    void waitsForRepliesOnlyUntilTheRequestedDeadline() throws IOException {
        run();
        assertEquals(Duration.ofSeconds(1).toNanos(), transport.nanos);
    }

    @Test
    void aHelloReplyProvesOnlyServerHealth() throws IOException {
        transport.incoming.add(new DatagramTransport.Received(
                new PpcsPacket(1, PpcsMessages.address(transport.localAddress())).encode(), SERVER));
        assertEquals(ProbeResult.Status.TIMED_OUT, run().status());
    }

    @Test
    void confirmsADidMatchedPeerWithAnAliveExchange() throws IOException {
        queue(0x40, PpcsMessages.address(PEER), SERVER);
        queue(0x42, PpcsMessages.identity(DID), PEER);
        queue(0xe1, new byte[0], PEER);
        assertEquals(ProbeResult.Status.REACHABLE_P2P, run().status());
    }

    @Test
    void closesThePeerSessionAfterTheAvailabilityCheck() throws IOException {
        queue(0x40, PpcsMessages.address(PEER), SERVER);
        queue(0x42, PpcsMessages.identity(DID), PEER);
        queue(0xe1, new byte[0], PEER);
        run();
        assertEquals(0xf0, transport.sent.getLast().packet.type());
    }

    @Test
    void retransmitsRendezvousRequestsAfterPacketLoss() throws IOException {
        run();
        assertEquals(2, transport.sent.stream().filter(s -> s.packet.type() == 0x20).count());
    }

    @Test
    void cancelsWithoutWaitingForTheDeadline() throws IOException {
        var result = new UdpSessionProbe(transport, () -> transport.nanos).check(
                new SessionRequest(DID, List.of(SERVER), Duration.ofSeconds(1), false),
                () -> transport.nanos >= 100_000_000L, message -> { });
        assertEquals(ProbeResult.Status.CANCELLED, result.status());
    }

    @Test
    void ignoresMalformedDatagramsInsteadOfCrashingTheCheck() throws IOException {
        transport.incoming.add(new DatagramTransport.Received(new byte[0], SERVER));
        assertEquals(ProbeResult.Status.INCONCLUSIVE, run().status());
    }

    @Test
    void canConfirmAMatchingLanDeviceWithoutServerAssistance() throws IOException {
        queue(0x41, PpcsMessages.identity(DID), PEER);
        queue(0x42, PpcsMessages.identity(DID), PEER);
        queue(0xe1, new byte[0], PEER);
        assertEquals(ProbeResult.Status.REACHABLE_P2P, run(true).status());
    }

    @Test
    void activelyDiscoversLanDevicesOnTheSdkDiscoveryPort() throws IOException {
        run(true);
        assertTrue(transport.sent.stream().anyMatch(s -> s.packet.type() == 0x30
                && s.destination.equals(new InetSocketAddress("255.255.255.255", 32108))));
    }

    @Test
    void noReplyInALanOnlySessionIsInconclusiveNotAVacuousAllServerRejection() throws IOException {
        var result = new UdpSessionProbe(transport, () -> transport.nanos).check(
                new SessionRequest(DID, List.of(), Duration.ofSeconds(1), true), () -> false, message -> {});
        assertEquals(ProbeResult.Status.INCONCLUSIVE, result.status());
        assertTrue(transport.sent.stream().allMatch(sent -> sent.packet.type() == 0x30));
        assertFalse(result.serverResponded());
    }

    @Test
    void confirmsADidThroughTheLegacyUdpRelayHandshake() throws IOException {
        queue(0x69, ByteBuffer.allocate(20).putInt(0x01000000).put(PpcsMessages.address(PEER)).array(), SERVER);
        queue(0x71, new byte[0], PEER);
        queue(0x73, ByteBuffer.allocate(8).putInt(0x11223344).putShort((short) PEER.getPort()).array(), PEER);
        queue(0x82, ByteBuffer.allocate(20).put(PpcsMessages.address(PEER)).putInt(0x11223344).array(), SERVER);
        queue(0x84, PpcsMessages.identity(DID), PEER);
        queue(0xe1, new byte[0], PEER);
        assertEquals(ProbeResult.Status.REACHABLE_RELAY, run().status());
    }

    @Test
    void requestsARelayListWhenTheDirectAttemptHasNotConnected() throws IOException {
        run();
        assertTrue(transport.sent.stream().anyMatch(s -> s.packet.type() == 0x67));
    }

    @Test
    void reportsPeerNetworkAndElapsedTimeForTheUser() throws IOException {
        var publicEndpoint = new InetSocketAddress("203.0.113.4", 55000);
        queue(1, PpcsMessages.address(publicEndpoint), SERVER);
        queue(0x40, PpcsMessages.address(PEER), SERVER);
        queue(0x42, PpcsMessages.identity(DID), PEER);
        queue(0xe1, new byte[0], PEER);
        assertEquals(new ProbeResult(ProbeResult.Status.REACHABLE_P2P,
                "DID-matched peer confirmed by alive acknowledgement.", true,
                Optional.of(PEER), Optional.of(publicEndpoint), Duration.ofMillis(400),
                new P2pInformation(Optional.of(TransportProtocol.PPCS), P2pInformation.ConnectionMode.DIRECT_P2P,
                        Optional.of(transport.localAddress()), List.of(SERVER), List.of(SERVER),
                        List.of(PEER), List.of(), Map.of())), run());
    }

    @Test
    void retainsTheRawServerRejectionInsteadOfCallingTheDevicePoweredOff() throws IOException {
        queue(0x21, new byte[] {(byte) 0xfc, 0, 0, 0}, SERVER);
        var result = run();
        assertEquals(ProbeResult.Status.SERVER_REJECTED, result.status());
        assertTrue(result.detail().contains("-4"));
    }

    @Test
    void retransmitsTheAliveProbeIfTheFirstAcknowledgementIsLost() throws IOException {
        queue(0x40, PpcsMessages.address(PEER), SERVER);
        queue(0x42, PpcsMessages.identity(DID), PEER);
        run();
        assertTrue(transport.sent.stream().filter(s -> s.packet.type() == 0xe0).count() >= 2);
    }

    @Test
    void closesAPartialSessionWhenItsAliveCheckTimesOut() throws IOException {
        queue(0x40, PpcsMessages.address(PEER), SERVER);
        queue(0x42, PpcsMessages.identity(DID), PEER);
        run();
        assertEquals(0xf0, transport.sent.getLast().packet.type());
    }

    @Test
    void emitsDistinctServerConnectionAndConfirmationStages() throws IOException {
        queue(1, PpcsMessages.address(transport.localAddress()), SERVER);
        queue(0x40, PpcsMessages.address(PEER), SERVER);
        queue(0x42, PpcsMessages.identity(DID), PEER);
        queue(0xe1, new byte[0], PEER);
        var stages = new ArrayList<String>();
        new UdpSessionProbe(transport, () -> transport.nanos).check(
                new SessionRequest(DID, List.of(SERVER), Duration.ofSeconds(1), false), () -> false, stages::add);
        assertEquals(List.of("Checking PPCS servers.", "Connecting by DID: " + DID.value(),
                "PPCS server replied: " + SERVER, "DID-matched peer ready: " + PEER,
                "Confirmed direct P2P reachability."), stages);
    }

    @Test
    void aCancellationDuringReceiveWinsOverALateSuccessReply() throws IOException {
        var cancelled = new AtomicBoolean();
        var io = new FakeTransport() {
            @Override
            public Optional<Received> receive(Duration wait) {
                var received = super.receive(wait);
                if (nanos >= 300_000_000L) {
                    cancelled.set(true);
                }
                return received;
            }
        };
        queue(0x40, PpcsMessages.address(PEER), SERVER);
        queue(0x42, PpcsMessages.identity(DID), PEER);
        queue(0xe1, new byte[0], PEER);
        io.incoming.addAll(transport.incoming);
        assertEquals(ProbeResult.Status.CANCELLED, new UdpSessionProbe(io, () -> io.nanos).check(
                new SessionRequest(DID, List.of(SERVER), Duration.ofSeconds(1), false),
                cancelled::get, message -> { }).status());
    }

    @Test
    void aReplyAtTheDeadlineCannotTurnATimeoutIntoSuccess() throws IOException {
        var io = new FakeTransport() {
            @Override
            public Optional<Received> receive(Duration wait) {
                var received = super.receive(wait);
                if (nanos >= 300_000_000L) {
                    nanos = Duration.ofSeconds(1).toNanos();
                }
                return received;
            }
        };
        queue(0x40, PpcsMessages.address(PEER), SERVER);
        queue(0x42, PpcsMessages.identity(DID), PEER);
        queue(0xe1, new byte[0], PEER);
        io.incoming.addAll(transport.incoming);
        assertEquals(ProbeResult.Status.TIMED_OUT, new UdpSessionProbe(io, () -> io.nanos).check(
                new SessionRequest(DID, List.of(SERVER), Duration.ofSeconds(1), false),
                () -> false, message -> { }).status());
    }

    @Test
    void retriesEveryLegOfTheRelayHandshakeWhenPacketsAreLost() throws IOException {
        var io = new FakeTransport() {
            final HashMap<Integer, Integer> attempts = new HashMap<>();

            @Override
            public void send(byte[] bytes, InetSocketAddress destination) throws IOException {
                super.send(bytes, destination);
                int type = PpcsPacket.decode(bytes).type();
                int count = attempts.merge(type, 1, Integer::sum);
                if (type == 0x67 && count == 1) {
                    reply(0x69, ByteBuffer.allocate(20).putInt(0x01000000).put(PpcsMessages.address(PEER)).array(), SERVER);
                } else if (type == 0x70 && count == 2) {
                    reply(0x71, new byte[0], PEER);
                } else if (type == 0x72 && count == 2) {
                    reply(0x73, ByteBuffer.allocate(8).putInt(0x11223344).putShort((short) PEER.getPort()).array(), PEER);
                } else if (type == 0x80 && count == 2) {
                    reply(0x82, ByteBuffer.allocate(20).put(PpcsMessages.address(PEER)).putInt(0x11223344).array(), SERVER);
                } else if (type == 0x83 && count == 2) {
                    reply(0x84, PpcsMessages.identity(DID), PEER);
                } else if (type == 0xe0) {
                    reply(0xe1, new byte[0], PEER);
                }
            }

            void reply(int type, byte[] payload, InetSocketAddress source) {
                incoming.add(new Received(new PpcsPacket(type, payload).encode(), source));
            }
        };
        assertEquals(ProbeResult.Status.REACHABLE_RELAY, new UdpSessionProbe(io, () -> io.nanos).check(
                new SessionRequest(DID, List.of(SERVER), Duration.ofSeconds(6), false),
                () -> false, message -> { }).status());
    }

    @Test
    void malformedPeerAddressesAreIgnoredAsUnconfirmedInput() throws IOException {
        queue(0x40, new byte[16], SERVER);
        assertEquals(ProbeResult.Status.INCONCLUSIVE, run().status());
    }

    @Test
    void aCloseDatagramFailureDoesNotEraseConfirmedReachability() throws IOException {
        var io = new FakeTransport() {
            @Override
            public void send(byte[] bytes, InetSocketAddress destination) throws IOException {
                if (PpcsPacket.decode(bytes).type() == 0xf0) {
                    throw new IOException("Close datagram failed");
                }
                super.send(bytes, destination);
            }
        };
        queue(0x40, PpcsMessages.address(PEER), SERVER);
        queue(0x42, PpcsMessages.identity(DID), PEER);
        queue(0xe1, new byte[0], PEER);
        io.incoming.addAll(transport.incoming);
        assertEquals(ProbeResult.Status.REACHABLE_P2P, new UdpSessionProbe(io, () -> io.nanos).check(
                new SessionRequest(DID, List.of(SERVER), Duration.ofSeconds(1), false),
                () -> false, message -> { }).status());
    }

    @Test
    void keepsOneOwnerForTheFirstDidMatchedSession() throws IOException {
        var secondPeer = new InetSocketAddress("127.0.0.1", 32109);
        queue(0x40, PpcsMessages.address(PEER), SERVER);
        queue(0x40, PpcsMessages.address(secondPeer), SERVER);
        queue(0x42, PpcsMessages.identity(DID), PEER);
        queue(0x42, PpcsMessages.identity(DID), secondPeer);
        queue(0xe1, new byte[0], PEER);
        var result = run();
        assertEquals(ProbeResult.Status.REACHABLE_P2P, result.status());
        assertEquals(Optional.of(PEER), result.peer());
        assertEquals(List.of(PEER, secondPeer), result.p2pInformation().deviceCandidates());
    }

    @Test
    void retriesADirectPunchWhenTheFirstReadyReplyIsLost() throws IOException {
        var io = new FakeTransport() {
            int punches;

            @Override
            public void send(byte[] bytes, InetSocketAddress destination) throws IOException {
                super.send(bytes, destination);
                int type = PpcsPacket.decode(bytes).type();
                if (type == 0x41 && ++punches == 2) {
                    incoming.add(new Received(new PpcsPacket(0x42, PpcsMessages.identity(DID)).encode(), PEER));
                } else if (type == 0xe0) {
                    incoming.add(new Received(new PpcsPacket(0xe1, new byte[0]).encode(), PEER));
                }
            }
        };
        io.incoming.add(new DatagramTransport.Received(new PpcsPacket(0x40, PpcsMessages.address(PEER)).encode(), SERVER));
        assertEquals(ProbeResult.Status.REACHABLE_P2P, new UdpSessionProbe(io, () -> io.nanos).check(
                new SessionRequest(DID, List.of(SERVER), Duration.ofSeconds(1), false),
                () -> false, message -> { }).status());
    }

    @Test
    void confirmsAnXqPeerUsingAsciiDidPacketsInBothDirections() throws IOException {
        byte[] identity = HexFormat.of().parseHex("464646462d3135383430392d4542444143000000");
        queue(0x40, PpcsMessages.address(PEER), SERVER);
        queue(0x42, identity, PEER);
        queue(0xe1, new byte[0], PEER);
        var result = new UdpSessionProbe(transport, () -> transport.nanos).check(
                new SessionRequest(DID, List.of(SERVER), Duration.ofSeconds(1), false, TransportProtocol.XQ),
                () -> false, message -> { });
        assertEquals(ProbeResult.Status.REACHABLE_P2P, result.status());
        assertEquals(Optional.of(TransportProtocol.XQ), result.p2pInformation().protocol());
        assertEquals(P2pInformation.ConnectionMode.DIRECT_P2P, result.p2pInformation().mode());
        assertEquals(List.of(PEER), result.p2pInformation().deviceCandidates());
        assertArrayEquals(identity, Arrays.copyOf(transport.sent.stream()
                .filter(s -> s.packet.type() == 0x20).findFirst().orElseThrow().packet.payload(), 20));
    }

    @Test
    void completesTheXqRelaySequenceWithAsciiIdentityAndAnOpaqueCookie() throws IOException {
        byte[] identity = HexFormat.of().parseHex("464646462d3135383430392d4542444143000000");
        queue(0x69, ByteBuffer.allocate(20).putInt(0x01000000).put(PpcsMessages.address(PEER)).array(), SERVER);
        queue(0x71, new byte[0], PEER);
        queue(0x73, ByteBuffer.allocate(8).putInt(0x11223344).putShort((short) PEER.getPort()).array(), PEER);
        queue(0x82, ByteBuffer.allocate(20).put(PpcsMessages.address(PEER)).putInt(0x11223344).array(), SERVER);
        queue(0x84, identity, PEER);
        queue(0xe1, new byte[0], PEER);
        var result = new UdpSessionProbe(transport, () -> transport.nanos).check(
                new SessionRequest(DID, List.of(SERVER), Duration.ofSeconds(1), false, TransportProtocol.XQ),
                () -> false, message -> { });
        assertEquals(ProbeResult.Status.REACHABLE_RELAY, result.status());
        assertEquals(Optional.of(TransportProtocol.XQ), result.p2pInformation().protocol());
        assertEquals(P2pInformation.ConnectionMode.UDP_RELAY, result.p2pInformation().mode());
        assertEquals(List.of(PEER), result.p2pInformation().relayCandidates());
        assertArrayEquals(identity, Arrays.copyOf(transport.sent.stream()
                .filter(s -> s.packet.type() == 0x80).findFirst().orElseThrow().packet.payload(), 20));
        assertArrayEquals(identity, Arrays.copyOfRange(transport.sent.stream()
                .filter(s -> s.packet.type() == 0x83).findFirst().orElseThrow().packet.payload(), 4, 24));
    }

    @Test
    void reportsTheSelectedTransportInProgressAndFailureDetails() throws IOException {
        queue(1, PpcsMessages.address(transport.localAddress()), SERVER);
        var messages = new ArrayList<String>();
        var result = new UdpSessionProbe(transport, () -> transport.nanos).check(
                new SessionRequest(DID, List.of(SERVER), Duration.ofSeconds(1), false, TransportProtocol.XQ),
                () -> false, messages::add);
        assertTrue(messages.contains("Checking XQ servers."));
        assertTrue(result.detail().contains("XQ server responded"));
    }

    @Test
    void acknowledgesTheSelectedXqPeersHeartbeatDuringConfirmation() throws IOException {
        queue(0x40, PpcsMessages.address(PEER), SERVER);
        queue(0x42, HexFormat.of().parseHex("464646462d3135383430392d4542444143000000"), PEER);
        queue(0xe0, new byte[0], PEER);
        queue(0xe1, new byte[0], PEER);
        new UdpSessionProbe(transport, () -> transport.nanos).check(
                new SessionRequest(DID, List.of(SERVER), Duration.ofSeconds(1), false, TransportProtocol.XQ),
                () -> false, message -> { });
        assertTrue(transport.sent.stream().anyMatch(s -> s.packet.type() == 0xe1 && s.destination.equals(PEER)));
    }

    @Test
    void anUnreachableServerDoesNotAbortTheCheckForTheOthers() throws IOException {
        var unreachable = new InetSocketAddress("127.0.0.2", 32100);
        var io = new FakeTransport() {
            @Override
            public void send(byte[] bytes, InetSocketAddress destination) throws IOException {
                if (destination.equals(unreachable)) {
                    throw new IOException("No route to host");
                }
                super.send(bytes, destination);
            }
        };
        queue(0x40, PpcsMessages.address(PEER), SERVER);
        queue(0x42, PpcsMessages.identity(DID), PEER);
        queue(0xe1, new byte[0], PEER);
        io.incoming.addAll(transport.incoming);
        var messages = new ArrayList<String>();
        var result = new UdpSessionProbe(io, () -> io.nanos).check(
                new SessionRequest(DID, List.of(unreachable, SERVER), Duration.ofSeconds(1), false),
                () -> false, messages::add);
        assertEquals(ProbeResult.Status.REACHABLE_P2P, result.status());
        assertEquals(List.of("Could not send to " + unreachable + ": No route to host"),
                messages.stream().filter(message -> message.startsWith("Could not send")).toList());
    }

    @Test
    void aFailedLanBroadcastDoesNotAbortTheCheck() throws IOException {
        var io = new FakeTransport() {
            @Override
            public void send(byte[] bytes, InetSocketAddress destination) throws IOException {
                if (destination.getAddress().getHostAddress().equals("255.255.255.255")) {
                    throw new IOException("Can't assign requested address");
                }
                super.send(bytes, destination);
            }
        };
        assertEquals(ProbeResult.Status.INCONCLUSIVE, new UdpSessionProbe(io, () -> io.nanos).check(
                new SessionRequest(DID, List.of(SERVER), Duration.ofSeconds(1), true),
                () -> false, message -> { }).status());
    }

    @Test
    void failsAsALocalNetworkErrorWhenNoDatagramCanBeSent() {
        var io = new FakeTransport() {
            @Override
            public void send(byte[] bytes, InetSocketAddress destination) throws IOException {
                throw new IOException("Network is unreachable");
            }
        };
        var error = assertThrows(IOException.class, () -> new UdpSessionProbe(io, () -> io.nanos).check(
                new SessionRequest(DID, List.of(SERVER), Duration.ofSeconds(1), true),
                () -> false, message -> { }));
        assertTrue(error.getMessage().contains("Network is unreachable"));
    }

    @Test
    void aServerListedTwiceStillCountsAsOneRejection() throws IOException {
        queue(0x21, new byte[] {(byte) 0xfc, 0, 0, 0}, SERVER);
        assertEquals(ProbeResult.Status.SERVER_REJECTED, new UdpSessionProbe(transport, () -> transport.nanos).check(
                new SessionRequest(DID, List.of(SERVER, SERVER), Duration.ofSeconds(1), false),
                () -> false, message -> { }).status());
    }

    @Test
    void ignoresMessageTypesOutsideTheAvailabilityProfile() throws IOException {
        queue(0x99, new byte[0], SERVER);
        assertEquals(ProbeResult.Status.INCONCLUSIVE, run().status());
    }

    @Test
    void retainsReturnedDeviceAddressesEvenWhenThePeerHandshakeDoesNotComplete() throws IOException {
        var candidate = new InetSocketAddress("198.51.100.8", 32108);
        var clientPublic = new InetSocketAddress("203.0.113.4", 55000);
        queue(1, PpcsMessages.address(clientPublic), SERVER);
        queue(0x21, new byte[4], SERVER);
        queue(0x40, PpcsMessages.address(candidate), SERVER);
        queue(0x40, PpcsMessages.address(candidate), SERVER);
        var result = run();
        assertEquals(ProbeResult.Status.TIMED_OUT, result.status());
        assertTrue(result.peer().isEmpty(), "A server-reported IP is not a confirmed peer");
        assertEquals(Optional.of(clientPublic), result.publicEndpoint());
        var information = result.p2pInformation();
        assertEquals(Optional.of(TransportProtocol.PPCS), information.protocol());
        assertEquals(P2pInformation.ConnectionMode.NONE, information.mode());
        assertEquals(Optional.of(transport.localAddress()), information.localEndpoint());
        assertEquals(List.of(SERVER), information.resolvedServers());
        assertEquals(List.of(SERVER), information.respondingServers());
        assertEquals(List.of(candidate), information.deviceCandidates());
        assertEquals(Map.of(SERVER, 0), information.serverStatuses());
    }

    @Test
    void distinguishesALanDiscoveredSessionFromServerAssistedDirectP2p() throws IOException {
        queue(0x41, PpcsMessages.identity(DID), PEER);
        queue(0x42, PpcsMessages.identity(DID), PEER);
        queue(0xe1, new byte[0], PEER);
        var result = run(true);
        assertEquals(P2pInformation.ConnectionMode.LAN, result.p2pInformation().mode());
        assertEquals(List.of(PEER), result.p2pInformation().deviceCandidates());
        assertEquals(List.of(), result.p2pInformation().respondingServers());
        assertFalse(result.serverResponded());
    }

    @Test
    void aServerAdvertisedPrivateAddressIsNotMislabelledAsLanDiscovery() throws IOException {
        queue(0x40, PpcsMessages.address(PEER), SERVER);
        queue(0x42, PpcsMessages.identity(DID), PEER);
        queue(0xe1, new byte[0], PEER);
        assertEquals(P2pInformation.ConnectionMode.DIRECT_P2P, run(true).p2pInformation().mode());
    }

    @Test
    void retainsRelayAddressesSeparatelyFromDeviceAddresses() throws IOException {
        var allocated = new InetSocketAddress("127.0.0.1", 57000);
        queue(0x69, ByteBuffer.allocate(20).putInt(0x01000000).put(PpcsMessages.address(PEER)).array(), SERVER);
        queue(0x71, new byte[0], PEER);
        queue(0x73, ByteBuffer.allocate(8).putInt(0x11223344).putShort((short) allocated.getPort()).array(), PEER);
        queue(0x82, ByteBuffer.allocate(20).put(PpcsMessages.address(allocated)).putInt(0x11223344).array(), SERVER);
        queue(0x84, PpcsMessages.identity(DID), allocated);
        queue(0xe1, new byte[0], allocated);
        var result = run();
        assertEquals(ProbeResult.Status.REACHABLE_RELAY, result.status());
        assertEquals(Optional.of(allocated), result.peer());
        assertEquals(P2pInformation.ConnectionMode.UDP_RELAY, result.p2pInformation().mode());
        assertEquals(List.of(PEER, allocated), result.p2pInformation().relayCandidates());
        assertEquals(List.of(), result.p2pInformation().deviceCandidates());
    }

    @Test
    void aReturnedRelayAddressWithoutPeerConfirmationStillRetainsServerEvidence() throws IOException {
        queue(0x82, ByteBuffer.allocate(20).put(PpcsMessages.address(PEER)).putInt(0x11223344).array(), SERVER);
        var result = run();
        assertEquals(ProbeResult.Status.TIMED_OUT, result.status());
        assertEquals(List.of(SERVER), result.p2pInformation().respondingServers());
        assertEquals(List.of(PEER), result.p2pInformation().relayCandidates());
        assertTrue(result.peer().isEmpty());
    }

    @Test
    void cancellationRetainsAnAddressAlreadyReturnedByTheServer() throws IOException {
        queue(0x40, PpcsMessages.address(PEER), SERVER);
        var result = new UdpSessionProbe(transport, () -> transport.nanos).check(
                new SessionRequest(DID, List.of(SERVER), Duration.ofSeconds(1), false),
                () -> transport.nanos >= 200_000_000L, message -> {});
        assertEquals(ProbeResult.Status.CANCELLED, result.status());
        assertEquals(List.of(PEER), result.p2pInformation().deviceCandidates());
    }

    @Test
    void malformedAndUntrustedRepliesDoNotBecomeDisplayedP2pInformation() throws IOException {
        var unknown = new InetSocketAddress("127.0.0.2", 32100);
        queue(0x40, PpcsMessages.address(PEER), unknown);
        queue(0x21, new byte[4], unknown);
        queue(1, new byte[16], SERVER);
        queue(0x40, new byte[16], SERVER);
        queue(0x69, ByteBuffer.allocate(36).putInt(0x02000000).put(PpcsMessages.address(PEER))
                .put(new byte[16]).array(), SERVER);
        var result = run();
        assertEquals(List.of(), result.p2pInformation().deviceCandidates());
        assertEquals(List.of(), result.p2pInformation().relayCandidates());
        assertEquals(List.of(), result.p2pInformation().respondingServers());
        assertEquals(Map.of(), result.p2pInformation().serverStatuses());
    }

    @Test
    void exposesRawAcknowledgementBytesWithoutTranslatingThemIntoSdkErrorCodes() throws IOException {
        queue(0x21, new byte[] {(byte) 0xfc, 0, 0, 0}, SERVER);
        var result = run();
        assertEquals(Map.of(SERVER, -4), result.p2pInformation().serverStatuses());
        assertEquals(List.of(SERVER), result.p2pInformation().respondingServers());
    }

    @Test
    void recordsTheLastRawStatusPerServerInReplyOrderAndDeduplicatesResponders() throws IOException {
        var secondServer = new InetSocketAddress("127.0.0.2", 32100);
        queue(0x21, new byte[] {(byte) 0xfc, 0, 0, 0}, secondServer);
        queue(0x21, new byte[] {1, 0, 0, 0}, SERVER);
        queue(0x21, new byte[4], secondServer);
        var result = new UdpSessionProbe(transport, () -> transport.nanos).check(
                new SessionRequest(DID, List.of(SERVER, secondServer), Duration.ofSeconds(1), false),
                () -> false, message -> {});
        assertEquals(List.of(SERVER, secondServer), result.p2pInformation().resolvedServers());
        assertEquals(List.of(secondServer, SERVER), result.p2pInformation().respondingServers());
        assertEquals(List.of(secondServer, SERVER), new ArrayList<>(result.p2pInformation().serverStatuses().keySet()));
        assertEquals(Map.of(SERVER, 1, secondServer, 0), result.p2pInformation().serverStatuses());
    }

    @Test
    void eachProbeHasAnIndependentNetworkSnapshotWithoutPreviousDeviceAddresses() throws IOException {
        var probe = new UdpSessionProbe(transport, () -> transport.nanos);
        queue(0x40, PpcsMessages.address(PEER), SERVER);
        var first = probe.check(new SessionRequest(DID, List.of(SERVER), Duration.ofSeconds(1), false),
                () -> false, message -> {});
        var second = probe.check(new SessionRequest(DID, List.of(SERVER), Duration.ofSeconds(1), false),
                () -> false, message -> {});
        assertEquals(List.of(PEER), first.p2pInformation().deviceCandidates());
        assertEquals(List.of(), second.p2pInformation().deviceCandidates());
        assertEquals(List.of(), second.p2pInformation().respondingServers());
    }

    void queue(int type, byte[] payload, InetSocketAddress source) {
        transport.incoming.add(new DatagramTransport.Received(new PpcsPacket(type, payload).encode(), source));
    }

    static class FakeTransport extends FakeDatagramTransport {
        final List<Sent> sent = new ArrayList<>();

        @Override
        public void send(byte[] bytes, InetSocketAddress destination) throws IOException {
            sent.add(new Sent(PpcsPacket.decode(bytes), destination));
        }

    }

    record Sent(PpcsPacket packet, InetSocketAddress destination) {
    }
}
