package com.ivanocj.cameras.availability.infrastructure.protocol;

import com.ivanocj.cameras.availability.domain.model.DeviceId;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class ProtocolMessages {
    private static final int IDENTITY_SIZE = 20;
    private static final int EXTENDED_PEER_REPLY_SIZE = 24;
    private final TransportProtocol protocol;

    public ProtocolMessages(TransportProtocol protocol) {
        if (!protocol.legacyUdp()) {
            throw new IllegalArgumentException("The legacy UDP codec supports PPCS and XQ only.");
        }
        this.protocol = protocol;
    }

    public byte[] identity(DeviceId did) {
        protocol.validate(did);
        if (protocol == TransportProtocol.PPCS) {
            return PpcsMessages.identity(did);
        }
        return ByteBuffer.allocate(IDENTITY_SIZE).put(did.value().getBytes(StandardCharsets.US_ASCII)).array();
    }

    /**
     * Matches the full wire identity in a documented peer reply. PPCS punch/ready replies may append
     * three API-version bytes and one reserved byte (pppp.fdesc's EX layouts). Those four bytes are
     * metadata, not identity. XQ and relay-ready replies retain their legacy 20-byte payloads.
     */
    public boolean matchesPeerIdentity(MessageType type, byte[] payload, byte[] expectedIdentity) {
        if (type != MessageType.PUNCH_PACKET && type != MessageType.P2P_READY && type != MessageType.RELAY_READY) {
            return false;
        }
        boolean supportedLength = payload.length == IDENTITY_SIZE
                || (protocol == TransportProtocol.PPCS && payload.length == EXTENDED_PEER_REPLY_SIZE
                    && (type == MessageType.PUNCH_PACKET || type == MessageType.P2P_READY));
        return supportedLength && expectedIdentity.length == IDENTITY_SIZE
                && Arrays.equals(payload, 0, IDENTITY_SIZE, expectedIdentity, 0, IDENTITY_SIZE);
    }

    public PpcsPacket connectRequest(DeviceId did, InetSocketAddress local) {
        return new PpcsPacket(MessageType.P2P_REQUEST,
                ByteBuffer.allocate(36).put(identity(did)).put(PpcsMessages.address(local)).array());
    }

    public PpcsPacket relayRequest(DeviceId did, InetSocketAddress relay, int cookie) {
        return new PpcsPacket(MessageType.RELAY_REQUEST, ByteBuffer.allocate(40)
                .put(identity(did)).put(PpcsMessages.address(relay)).putInt(cookie).array());
    }

    public PpcsPacket relayPunch(DeviceId did, int cookie) {
        return new PpcsPacket(MessageType.RELAY_PUNCH, ByteBuffer.allocate(28).putInt(cookie).put(identity(did)).array());
    }
}
