package com.ivanocj.cameras.availability.infrastructure.protocol;

import java.io.IOException;
import java.util.Arrays;
import static com.ivanocj.cameras.availability.infrastructure.protocol.YooseeWire.*;

/** Fresh-block decoder for the supplied current SDK's QuickLZ level 2; all input/output references are bounded. */
public final class YooseeQuickLz {
    private YooseeQuickLz() { }
    public static byte[] decompress(byte[] source, int expected) throws IOException {
        require(source.length >= 3 && expected > 0 && expected <= 32767);
        int width = (source[0] & 2) == 0 ? 1 : 4, header = 1 + 2 * width;
        require(source.length >= header && ((source[0] >>> 2) & 3) == 2);
        int compressed = width == 1 ? source[1] & 255 : i32(source, 1);
        int decompressed = width == 1 ? source[2] & 255 : i32(source, 5);
        require(compressed == source.length && decompressed == expected);
        if ((source[0] & 1) == 0) {
            require(source.length == header + expected);
            return Arrays.copyOfRange(source, header, source.length);
        }
        var output = new byte[expected];
        var table = new int[2048][4];
        for (var slots : table) { Arrays.fill(slots, -1); }
        var counts = new int[2048];
        int input = header, out = 0, control = 1, hashed = -1;
        while (out < expected) {
            if (control == 1) {
                require(input + 4 <= source.length);
                control = i32(source, input);
                require(control < 0); // High-bit sentinel; prevents unchecked zero-control tails.
                input += 4;
            }
            if (out >= expected - 11) {
                require(input < source.length);
                output[out++] = source[input++];
                control >>>= 1;
                continue;
            }
            if ((control & 1) == 0) {
                require(input < source.length);
                output[out++] = source[input++];
                control >>>= 1;
                while (hashed < out - 3) { update(output, ++hashed, table, counts); }
                continue;
            }
            require(input + 2 <= source.length);
            int token = u16(source, input), length = ((token >>> 2) & 7) + 2;
            int from = table[(token >>> 5) & 2047][token & 3];
            input += 2;
            if ((token & 28) == 0) {
                require(input < source.length);
                length = source[input++] & 255;
            }
            require(length >= 3 && from >= 0 && from <= out - 3 && length <= expected - out - 4);
            int start = out;
            for (int i = 0; i < length; i++) { output[out++] = output[from++]; }
            while (hashed < start) { update(output, ++hashed, table, counts); }
            hashed = out - 1;
            control >>>= 1;
        }
        // QuickLZ may keep up to three padding bytes for its 32-bit lookahead.
        require(input <= source.length && source.length - input <= 3);
        return output;
    }
    private static void update(byte[] data, int at, int[][] table, int[] counts) {
        int value = (data[at] & 255) | (data[at + 1] & 255) << 8 | (data[at + 2] & 255) << 16;
        int hash = (value ^ (value >>> 9) ^ (value >>> 13)) & 2047;
        table[hash][counts[hash]++ & 3] = at;
    }

    public static byte[] unpackFrame(byte[] frame) throws IOException {
        require(frame.length >= 24);
        int flags = i32(frame, 20);
        if ((flags & 1) == 0) { return frame; }
        int expected = (flags >>> 1) & 32767;
        var body = decompress(Arrays.copyOfRange(frame, 24, frame.length), expected);
        var expanded = Arrays.copyOf(frame, 24 + expected);
        System.arraycopy(body, 0, expanded, 24, body.length);
        buffer(expanded).putShort(2, (short) expanded.length).putInt(20, flags & 0xffff0000);
        return expanded;
    }
}
