package com.ivanocj.cameras.availability.testsupport;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Independent synthetic 0x70 producer, not the production reassembler. */
public final class YooseeFragmentFixtures {
    private YooseeFragmentFixtures() { }

    public static byte[] fragment(byte[] original, long identity, int sequence, int chunk, int index, boolean lan) {
        int length = Math.min(chunk, original.length - index * chunk);
        int count = (original.length + chunk - 1) / chunk;
        var bytes = new byte[24 + length];
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0x70).put((byte) 1).putShort((short) bytes.length).putLong(identity).putInt(sequence)
                .putShort((short) original.length).put((byte) (count | (lan ? 128 : 0))).put((byte) index);
        int mask = 0x7183 + index, check = mask;
        buffer.putShort(20, (short) mask);
        for (int at = 4; at < 20; at += 2) {
            int word = buffer.getShort(at) & 65535;
            check |= word;
            buffer.putShort(at, (short) (word ^ mask));
        }
        buffer.putShort(22, (short) check);
        System.arraycopy(original, index * chunk, bytes, 24, length);
        return bytes;
    }
}
