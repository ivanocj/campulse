package com.ivanocj.cameras.availability.infrastructure.protocol;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;

public record PpcsPacket(int type, byte[] payload) {
    public PpcsPacket(MessageType type, byte[] payload) {
        this(type.code(), payload);
    }

    public static PpcsPacket empty(MessageType type) {
        return new PpcsPacket(type, new byte[0]);
    }

    public static PpcsPacket decode(byte[] bytes) {
        if (bytes.length < 4 || bytes[0] != (byte) 0xf1) {
            throw new IllegalArgumentException("Not a PPCS datagram.");
        }
        if (Short.toUnsignedInt(ByteBuffer.wrap(bytes).getShort(2)) != bytes.length - 4) {
            throw new IllegalArgumentException("PPCS payload length does not match datagram length.");
        }
        return new PpcsPacket(Byte.toUnsignedInt(bytes[1]), Arrays.copyOfRange(bytes, 4, bytes.length));
    }

    public Optional<MessageType> messageType() {
        return MessageType.of(type);
    }

    public byte[] encode() {
        return ByteBuffer.allocate(4 + payload.length)
                .put((byte) 0xf1).put((byte) type).putShort((short) payload.length).put(payload).array();
    }
}
