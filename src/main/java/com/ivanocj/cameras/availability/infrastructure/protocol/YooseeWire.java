package com.ivanocj.cameras.availability.infrastructure.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Little-endian native GUTE fields; endpoint address/port octets remain network order. */
public final class YooseeWire {
    private YooseeWire() { }
    public static ByteBuffer buffer(byte[] data) { return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN); }
    public static int u16(byte[] data, int offset) { return buffer(data).getShort(offset) & 0xffff; }
    public static int i32(byte[] data, int offset) { return buffer(data).getInt(offset); }
    public static long i64(byte[] data, int offset) { return buffer(data).getLong(offset); }
    public static void require(boolean condition) throws IOException {
        if (!condition) { throw new IOException("Malformed or uncorrelated Yoosee frame."); }
    }
    public static int hash(byte[] data) {
        int value = 0x4e67c6a7;
        for (byte octet : data) { value ^= (octet & 255) + (value << 5) + (value >>> 2); }
        return value;
    }
    public static byte[] frame(int protocol, int command, int length, long identity, int sequence, int flags) {
        if (length < 24 || length > 65535) { throw new IllegalArgumentException("Invalid GUTE frame length."); }
        var frame = new byte[length];
        buffer(frame).put((byte) protocol).put((byte) command).putShort((short) length)
                .putLong(identity).putInt(sequence).putInt(0).putInt(flags);
        return frame;
    }
}
