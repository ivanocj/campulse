package com.ivanocj.cameras.availability.infrastructure.probe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ivanocj.cameras.availability.application.model.P2pInformation;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.model.SessionRequest;
import com.ivanocj.cameras.availability.application.port.DatagramTransport;
import com.ivanocj.cameras.availability.domain.model.DeviceId;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.infrastructure.protocol.PpcsMessages;
import com.ivanocj.cameras.availability.infrastructure.protocol.PpcsPacket;
import com.ivanocj.cameras.availability.infrastructure.protocol.ProtocolMessages;
import com.ivanocj.cameras.availability.testsupport.FakeDatagramTransport;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class ExtendedPeerReplyProbeTest {
    private static final DeviceId DID = new DeviceId("FFFF-158409-EBDAC");
    private static final InetSocketAddress SERVER = new InetSocketAddress("127.0.0.1", 32100);
    private static final InetSocketAddress PEER = new InetSocketAddress("127.0.0.1", 32108);
    private final FakeTransport transport = new FakeTransport();

    @Test
    void confirmsAnExtendedPpcsLanPunchAndReadyAfterAnAliveAcknowledgement() throws IOException {
        // pppp.fdesc: UID[20], API version[3], reserved[1]; payload size excludes the F1 header.
        transport.incoming.add(new DatagramTransport.Received(HexFormat.of().parseHex(
                "f1410018" + "464646460000000000026ac94542444143000000" + "01020300"), PEER));
        transport.incoming.add(new DatagramTransport.Received(HexFormat.of().parseHex(
                "f1420018" + "464646460000000000026ac94542444143000000" + "01020300"), PEER));
        transport.incoming.add(new DatagramTransport.Received(new PpcsPacket(0xe1, new byte[0]).encode(), PEER));

        var result = run(true, TransportProtocol.PPCS);

        assertEquals(ProbeResult.Status.REACHABLE_P2P, result.status());
        assertEquals(Optional.of(PEER), result.peer());
        assertEquals(P2pInformation.ConnectionMode.LAN, result.p2pInformation().mode());
        assertEquals(List.of(PEER), result.p2pInformation().deviceCandidates());
        assertEquals(0xf0, transport.sent.getLast().packet().type());
    }

    @ParameterizedTest
    @CsvSource({
            "true,false,false", "true,false,true", "true,true,false", "true,true,true",
            "false,false,false", "false,false,true", "false,true,false", "false,true,true"
    })
    void confirmsMixedLegacyAndExtendedRepliesForLanAndAdvertisedPeers(
            boolean lanSearch, boolean extendedPunch, boolean extendedReady) throws IOException {
        if (!lanSearch) {
            queue(0x40, PpcsMessages.address(PEER), SERVER);
        }
        queue(0x41, payload(TransportProtocol.PPCS, extendedPunch), PEER);
        queue(0x42, payload(TransportProtocol.PPCS, extendedReady), PEER);
        queue(0xe1, new byte[0], PEER);

        var result = run(lanSearch, TransportProtocol.PPCS);

        assertEquals(ProbeResult.Status.REACHABLE_P2P, result.status());
        assertEquals(Optional.of(PEER), result.peer());
        assertEquals(lanSearch ? P2pInformation.ConnectionMode.LAN : P2pInformation.ConnectionMode.DIRECT_P2P,
                result.p2pInformation().mode());
        assertEquals(List.of(PEER), result.p2pInformation().deviceCandidates());
        assertEquals(!lanSearch, result.serverResponded());
        assertEquals(0xf0, transport.sent.getLast().packet().type());
        for (var sent : transport.sent) {
            if (sent.packet().type() == 0x41) {
                assertArrayEquals(PpcsMessages.identity(DID), sent.packet().payload(),
                        "Reply recognition must not change the outgoing legacy identity");
            }
        }
    }

    @Test
    void anExtendedPunchWithoutReadyDoesNotConfirmOrSelectAPeer() throws IOException {
        queue(0x41, payload(TransportProtocol.PPCS, true), PEER);
        queue(0xe1, new byte[0], PEER);

        var result = run(true, TransportProtocol.PPCS);

        assertEquals(ProbeResult.Status.INCONCLUSIVE, result.status());
        assertTrue(result.peer().isEmpty());
        assertEquals(List.of(PEER), result.p2pInformation().deviceCandidates());
        assertFalse(transport.sent.stream().anyMatch(sent -> sent.packet().type() == 0xf0));
    }

    @Test
    void extendedReadyStillRequiresAliveConfirmationAndClosesAnIncompleteSession() throws IOException {
        queue(0x41, payload(TransportProtocol.PPCS, true), PEER);
        queue(0x42, payload(TransportProtocol.PPCS, true), PEER);

        var result = run(true, TransportProtocol.PPCS);

        assertEquals(ProbeResult.Status.INCONCLUSIVE, result.status());
        assertEquals(Optional.of(PEER), result.peer());
        assertEquals(P2pInformation.ConnectionMode.LAN, result.p2pInformation().mode());
        assertTrue(transport.sent.stream().filter(sent -> sent.packet().type() == 0xe0).count() >= 2);
        assertEquals(0xf0, transport.sent.getLast().packet().type());
    }

    @ParameterizedTest
    @ValueSource(ints = {0x41, 0x42})
    void rejectsAMismatchedUidInEitherExtendedPeerReply(int type) throws IOException {
        byte[] wrongUid = payload(TransportProtocol.PPCS, true);
        wrongUid[11] ^= 1; // The binary serial, not the API-version metadata.
        queue(0x41, type == 0x41 ? wrongUid : payload(TransportProtocol.PPCS, true), PEER);
        queue(0x42, type == 0x42 ? wrongUid : payload(TransportProtocol.PPCS, true), PEER);
        queue(0xe1, new byte[0], PEER);

        var result = run(true, TransportProtocol.PPCS);

        assertEquals(ProbeResult.Status.INCONCLUSIVE, result.status());
        assertTrue(result.peer().isEmpty());
        assertEquals(type == 0x41 ? List.of() : List.of(PEER), result.p2pInformation().deviceCandidates());
        assertFalse(transport.sent.stream().anyMatch(sent -> sent.packet().type() == 0xe0));
    }

    @ParameterizedTest
    @ValueSource(ints = {19, 21, 22, 23, 25, 28})
    void rejectsMalformedPunchWidthsBeforeAcceptingACandidate(int size) throws IOException {
        queue(0x41, Arrays.copyOf(payload(TransportProtocol.PPCS, true), size), PEER);
        queue(0x42, payload(TransportProtocol.PPCS, true), PEER);
        queue(0xe1, new byte[0], PEER);

        var result = run(true, TransportProtocol.PPCS);

        assertEquals(ProbeResult.Status.INCONCLUSIVE, result.status());
        assertTrue(result.peer().isEmpty());
        assertTrue(result.p2pInformation().deviceCandidates().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {19, 21, 22, 23, 25, 28})
    void rejectsMalformedReadyWidthsWithoutSelectingASession(int size) throws IOException {
        queue(0x41, payload(TransportProtocol.PPCS, true), PEER);
        queue(0x42, Arrays.copyOf(payload(TransportProtocol.PPCS, true), size), PEER);
        queue(0xe1, new byte[0], PEER);

        var result = run(true, TransportProtocol.PPCS);

        assertEquals(ProbeResult.Status.INCONCLUSIVE, result.status());
        assertTrue(result.peer().isEmpty());
        assertEquals(List.of(PEER), result.p2pInformation().deviceCandidates());
    }

    @Test
    void ignoresAnExtendedReadyFromAnEndpointThatWasNotACandidate() throws IOException {
        var unknown = new InetSocketAddress("127.0.0.2", 32108);
        queue(0x41, payload(TransportProtocol.PPCS, true), PEER);
        queue(0x42, payload(TransportProtocol.PPCS, true), unknown);
        queue(0xe1, new byte[0], unknown);

        var result = run(true, TransportProtocol.PPCS);

        assertEquals(ProbeResult.Status.INCONCLUSIVE, result.status());
        assertTrue(result.peer().isEmpty());
        assertEquals(List.of(PEER), result.p2pInformation().deviceCandidates());
    }

    @Test
    void doesNotTrustAnUnadvertisedPublicPeerJustBecauseItsExtendedUidMatches() throws IOException {
        var unknown = new InetSocketAddress("198.51.100.8", 32108);
        queue(0x41, payload(TransportProtocol.PPCS, true), unknown);
        queue(0x42, payload(TransportProtocol.PPCS, true), unknown);
        queue(0xe1, new byte[0], unknown);

        var result = run(true, TransportProtocol.PPCS);

        assertEquals(ProbeResult.Status.INCONCLUSIVE, result.status());
        assertTrue(result.peer().isEmpty());
        assertTrue(result.p2pInformation().deviceCandidates().isEmpty());
    }

    @Test
    void unsolicitedExtendedLanRepliesStillRequireTheLanSearchSetting() throws IOException {
        queue(0x41, payload(TransportProtocol.PPCS, true), PEER);
        queue(0x42, payload(TransportProtocol.PPCS, true), PEER);
        queue(0xe1, new byte[0], PEER);

        var result = run(false, TransportProtocol.PPCS);

        assertEquals(ProbeResult.Status.INCONCLUSIVE, result.status());
        assertTrue(result.peer().isEmpty());
        assertTrue(result.p2pInformation().deviceCandidates().isEmpty());
    }

    @Test
    void laterExtendedRepliesCannotReplaceTheSelectedPeer() throws IOException {
        var second = new InetSocketAddress("127.0.0.1", 32109);
        queue(0x41, payload(TransportProtocol.PPCS, true), PEER);
        queue(0x42, payload(TransportProtocol.PPCS, true), PEER);
        queue(0x41, payload(TransportProtocol.PPCS, true), second);
        queue(0x42, payload(TransportProtocol.PPCS, true), second);
        queue(0xe1, new byte[0], second);
        queue(0xe1, new byte[0], PEER);

        var result = run(true, TransportProtocol.PPCS);

        assertEquals(ProbeResult.Status.REACHABLE_P2P, result.status());
        assertEquals(Optional.of(PEER), result.peer());
        assertEquals(List.of(PEER, second), result.p2pInformation().deviceCandidates());
        assertEquals(PEER, transport.sent.getLast().destination());
        assertEquals(0xf0, transport.sent.getLast().packet().type());
    }

    @Test
    void cancellationStillWinsOverALateAliveAckForAnExtendedSession() throws IOException {
        queue(0x41, payload(TransportProtocol.PPCS, true), PEER);
        queue(0x42, payload(TransportProtocol.PPCS, true), PEER);
        queue(0xe1, new byte[0], PEER);

        var result = new UdpSessionProbe(transport, () -> transport.nanos).check(
                new SessionRequest(DID, List.of(SERVER), Duration.ofSeconds(1), true),
                () -> transport.nanos >= Duration.ofMillis(300).toNanos(), message -> {});

        assertEquals(ProbeResult.Status.CANCELLED, result.status());
        assertEquals(Optional.of(PEER), result.peer());
        assertEquals(0xf0, transport.sent.getLast().packet().type());
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,true", "true,true"})
    void xqDoesNotAcceptUndocumentedExtendedReplies(boolean extendedPunch, boolean extendedReady) throws IOException {
        queue(0x41, payload(TransportProtocol.XQ, extendedPunch), PEER);
        queue(0x42, payload(TransportProtocol.XQ, extendedReady), PEER);
        queue(0xe1, new byte[0], PEER);

        var result = run(true, TransportProtocol.XQ);

        assertEquals(ProbeResult.Status.INCONCLUSIVE, result.status());
        assertTrue(result.peer().isEmpty());
        assertEquals(extendedPunch ? List.of() : List.of(PEER), result.p2pInformation().deviceCandidates());
        assertEquals(Optional.of(TransportProtocol.XQ), result.p2pInformation().protocol());
    }

    @ParameterizedTest
    @EnumSource(value = TransportProtocol.class, names = {"PPCS", "XQ"})
    void relayReadyDoesNotAcceptTheDirectPeerExtension(TransportProtocol protocol) throws IOException {
        queue(0x82, ByteBuffer.allocate(20).put(PpcsMessages.address(PEER)).putInt(0x11223344).array(), SERVER);
        queue(0x84, payload(protocol, true), PEER);
        queue(0xe1, new byte[0], PEER);

        var result = run(false, protocol);

        assertEquals(ProbeResult.Status.TIMED_OUT, result.status());
        assertTrue(result.peer().isEmpty());
        assertTrue(result.p2pInformation().deviceCandidates().isEmpty());
        assertEquals(List.of(PEER), result.p2pInformation().relayCandidates());
    }

    @Test
    void aTruncatedExtendedFrameCannotBeReinterpretedAsALegacyReply() throws IOException {
        byte[] frame = new PpcsPacket(0x41, payload(TransportProtocol.PPCS, true)).encode();
        transport.incoming.add(new DatagramTransport.Received(Arrays.copyOf(frame, 24), PEER));
        queue(0x42, payload(TransportProtocol.PPCS, true), PEER);
        queue(0xe1, new byte[0], PEER);

        var result = run(true, TransportProtocol.PPCS);

        assertEquals(ProbeResult.Status.INCONCLUSIVE, result.status());
        assertTrue(result.peer().isEmpty());
        assertTrue(result.p2pInformation().deviceCandidates().isEmpty());
    }

    private ProbeResult run(boolean lanSearch, TransportProtocol protocol) throws IOException {
        return new UdpSessionProbe(transport, () -> transport.nanos).check(
                new SessionRequest(DID, List.of(SERVER), Duration.ofSeconds(1), lanSearch, protocol),
                () -> false, message -> {});
    }

    private void queue(int type, byte[] payload, InetSocketAddress source) {
        transport.incoming.add(new DatagramTransport.Received(new PpcsPacket(type, payload).encode(), source));
    }

    private static byte[] payload(TransportProtocol protocol, boolean extended) {
        byte[] identity = new ProtocolMessages(protocol).identity(DID);
        if (!extended) {
            return identity;
        }
        byte[] payload = Arrays.copyOf(identity, 24);
        payload[20] = 1;
        payload[21] = 2;
        payload[22] = 3;
        return payload;
    }

    private static final class FakeTransport extends FakeDatagramTransport {
        private final List<Sent> sent = new ArrayList<>();

        @Override
        public void send(byte[] bytes, InetSocketAddress destination) {
            sent.add(new Sent(PpcsPacket.decode(bytes), destination));
        }
    }

    private record Sent(PpcsPacket packet, InetSocketAddress destination) {}
}
