package com.ivanocj.cameras.availability.infrastructure.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ivanocj.cameras.availability.domain.model.DeviceId;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class PeerReplyIdentityTest {
    private static final DeviceId DID = new DeviceId("FFFF-158409-EBDAC");
    private static final String BINARY_IDENTITY = "464646460000000000026ac94542444143000000";

    @ParameterizedTest
    @EnumSource(value = TransportProtocol.class, names = {"PPCS", "XQ"})
    void retainsLegacyPeerRepliesForBothTransports(TransportProtocol protocol) {
        var messages = new ProtocolMessages(protocol);
        byte[] identity = messages.identity(DID);
        for (var type : peerReplyTypes()) {
            assertTrue(messages.matchesPeerIdentity(type, identity, identity), type::name);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "f1410018" + BINARY_IDENTITY + "01020300",
            "f1420018" + BINARY_IDENTITY + "03010000"
    })
    void recognizesTheExtendedDissectorByteVectors(String wireHex) {
        var packet = PpcsPacket.decode(HexFormat.of().parseHex(wireHex));
        var messages = new ProtocolMessages(TransportProtocol.PPCS);
        assertTrue(messages.matchesPeerIdentity(packet.messageType().orElseThrow(), packet.payload(),
                messages.identity(DID)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4, 19, 21, 22, 23, 25, 28, 36})
    void rejectsUndocumentedPayloadWidthsRatherThanIgnoringArbitraryTrailingBytes(int size) {
        for (var protocol : List.of(TransportProtocol.PPCS, TransportProtocol.XQ)) {
            var messages = new ProtocolMessages(protocol);
            byte[] identity = messages.identity(DID);
            for (var type : peerReplyTypes()) {
                assertFalse(messages.matchesPeerIdentity(type, Arrays.copyOf(identity, size), identity),
                        () -> protocol + " " + type + " payload size " + size);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = MessageType.class, names = {"PUNCH_PACKET", "P2P_READY"})
    void comparesEveryIdentityByteIncludingNullPaddingInExtendedReplies(MessageType type) {
        var messages = new ProtocolMessages(TransportProtocol.PPCS);
        byte[] identity = messages.identity(DID);
        for (int offset = 0; offset < identity.length; offset++) {
            byte[] payload = extended(identity);
            payload[offset] ^= (byte) 0xff;
            assertFalse(messages.matchesPeerIdentity(type, payload, identity), "Changed UID byte " + offset);
        }
    }

    @ParameterizedTest
    @EnumSource(value = MessageType.class, names = {"PUNCH_PACKET", "P2P_READY"})
    void treatsApiVersionAndTheReservedByteAsMetadataNotIdentity(MessageType type) {
        var messages = new ProtocolMessages(TransportProtocol.PPCS);
        byte[] identity = messages.identity(DID);
        byte[] payload = extended(identity);
        Arrays.fill(payload, 20, 24, (byte) 0xff);
        assertTrue(messages.matchesPeerIdentity(type, payload, identity));
    }

    @ParameterizedTest
    @EnumSource(value = MessageType.class, names = {"PUNCH_PACKET", "P2P_READY"})
    void doesNotApplyBinaryPpcsExtensionsToXq(MessageType type) {
        var messages = new ProtocolMessages(TransportProtocol.XQ);
        byte[] identity = messages.identity(DID);
        assertFalse(messages.matchesPeerIdentity(type, extended(identity), identity));
    }

    @ParameterizedTest
    @EnumSource(value = TransportProtocol.class, names = {"PPCS", "XQ"})
    void keepsRelayReadyRepliesAtTheirDocumentedLegacyWidth(TransportProtocol protocol) {
        var messages = new ProtocolMessages(protocol);
        byte[] identity = messages.identity(DID);
        assertFalse(messages.matchesPeerIdentity(MessageType.RELAY_READY, extended(identity), identity));
    }

    @ParameterizedTest
    @EnumSource(value = MessageType.class, mode = EnumSource.Mode.EXCLUDE,
            names = {"PUNCH_PACKET", "P2P_READY", "RELAY_READY"})
    void doesNotTreatUnrelatedMessageTypesAsPeerReplies(MessageType type) {
        for (var protocol : List.of(TransportProtocol.PPCS, TransportProtocol.XQ)) {
            var messages = new ProtocolMessages(protocol);
            byte[] identity = messages.identity(DID);
            assertFalse(messages.matchesPeerIdentity(type, identity, identity));
            assertFalse(messages.matchesPeerIdentity(type, extended(identity), identity));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 19, 21, 24})
    void requiresAnExpectedIdentityOfExactlyTwentyBytes(int size) {
        var messages = new ProtocolMessages(TransportProtocol.PPCS);
        byte[] identity = messages.identity(DID);
        assertFalse(messages.matchesPeerIdentity(MessageType.P2P_READY, extended(identity),
                Arrays.copyOf(identity, size)));
    }

    @Test
    void doesNotCrossMatchPpcsBinaryAndXqAsciiIdentities() {
        var ppcs = new ProtocolMessages(TransportProtocol.PPCS);
        var xq = new ProtocolMessages(TransportProtocol.XQ);
        byte[] binary = ppcs.identity(DID);
        byte[] ascii = xq.identity(DID);
        assertFalse(ppcs.matchesPeerIdentity(MessageType.P2P_READY, ascii, binary));
        assertFalse(ppcs.matchesPeerIdentity(MessageType.P2P_READY, extended(ascii), binary));
        assertFalse(xq.matchesPeerIdentity(MessageType.P2P_READY, binary, ascii));
    }

    @Test
    void matchingDoesNotModifyThePayloadOrExpectedIdentity() {
        var messages = new ProtocolMessages(TransportProtocol.PPCS);
        byte[] identity = messages.identity(DID);
        byte[] payload = extended(identity);
        byte[] originalIdentity = identity.clone();
        byte[] originalPayload = payload.clone();
        assertTrue(messages.matchesPeerIdentity(MessageType.P2P_READY, payload, identity));
        assertArrayEquals(originalIdentity, identity);
        assertArrayEquals(originalPayload, payload);
    }

    private static MessageType[] peerReplyTypes() {
        return new MessageType[] {MessageType.PUNCH_PACKET, MessageType.P2P_READY, MessageType.RELAY_READY};
    }

    private static byte[] extended(byte[] identity) {
        byte[] payload = Arrays.copyOf(identity, 24);
        payload[20] = 1;
        payload[21] = 2;
        payload[22] = 3;
        return payload;
    }
}
