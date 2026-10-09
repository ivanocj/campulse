package com.ivanocj.cameras.availability.infrastructure.protocol;

import com.ivanocj.cameras.availability.domain.model.DeviceId;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public final class PpcsMessages {
    private PpcsMessages() {
    }

    /** The SY binary DID identity: 8-byte prefix, big-endian serial, 8-byte check code. */
    public static byte[] identity(DeviceId did) {
        String[] parts = did.value().split("-");
        var buffer = ByteBuffer.allocate(20);
        buffer.put(parts[0].getBytes(StandardCharsets.US_ASCII));
        buffer.position(8);
        buffer.putInt((int) Long.parseLong(parts[1]));
        buffer.put(parts[2].getBytes(StandardCharsets.US_ASCII));
        return buffer.array();
    }

    public static byte[] address(InetSocketAddress endpoint) {
        var buffer = ByteBuffer.allocate(16);
        buffer.putShort((short) 2);
        buffer.order(ByteOrder.LITTLE_ENDIAN).putShort((short) endpoint.getPort());
        byte[] ip = endpoint.getAddress().getAddress();
        for (int i = 3; i >= 0; i--) {
            buffer.put(ip[i]);
        }
        return buffer.array();
    }

    public static InetSocketAddress readAddress(byte[] payload, int offset) {
        if (offset < 0 || offset > payload.length - 16 || payload[offset] != 0 || payload[offset + 1] != 2) {
            throw new IllegalArgumentException("Invalid PPCS IPv4 sockaddr.");
        }
        var buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int port = Short.toUnsignedInt(buffer.getShort(offset + 2));
        byte[] ip = {payload[offset + 7], payload[offset + 6], payload[offset + 5], payload[offset + 4]};
        try {
            return new InetSocketAddress(InetAddress.getByAddress(ip), port);
        } catch (UnknownHostException impossible) {
            throw new IllegalArgumentException(impossible);
        }
    }
}
