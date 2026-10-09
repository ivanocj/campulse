package com.ivanocj.cameras.availability.infrastructure.protocol;

import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import static com.ivanocj.cameras.availability.infrastructure.protocol.YooseeWire.*;

/** Shared RC5 primitive, with explicitly different GWell and IoTVideo wire/checksum rules. Per-session ownership. */
public final class GuteFrameCodec implements AutoCloseable {
    private final boolean iot;
    // Public protocol constant, NOT an account credential. Native rc5_ctx_setkey length is 0x0c, excluding the NUL.
    private final YooseeRc5 identity = new YooseeRc5(8, 6, "www.gwell.cc".getBytes(StandardCharsets.US_ASCII));
    private Optional<YooseeRc5> negotiated = Optional.empty();

    public GuteFrameCodec(TransportProtocol protocol) {
        if (protocol.legacyUdp()) { throw new IllegalArgumentException("GUTE is not PPCS/XQ."); }
        iot = protocol == TransportProtocol.IOTVIDEO;
    }

    public void negotiate(byte[] key) {
        negotiated.ifPresent(YooseeRc5::close);
        negotiated = Optional.of(new YooseeRc5(8, 6, key));
    }
    public int flags(byte[] data) { return iot ? i32(data, 20) : data[20] & 255; }
    public int mode(byte[] data) { return (flags(data) >>> (iot ? 16 : 0)) & 3; }
    public boolean response(byte[] data) { return (flags(data) & (iot ? 1 << 21 : 32)) != 0; }
    public boolean ack(byte[] data) { return (flags(data) & (iot ? 1 << 20 : 16)) != 0; }
    public int qos(byte[] data) { return (flags(data) >>> (iot ? 18 : 2)) & 3; }

    public byte[] encode(byte[] plain) throws IOException {
        validate(plain);
        var result = plain.clone();
        if (!response(result)) { buffer(result).putInt(16, checksum(result)); }
        transform(result, true);
        return result;
    }

    public byte[] decode(byte[] wire) throws IOException {
        validate(wire);
        var result = wire.clone();
        transform(result, false);
        if (!response(result)) { require(i32(result, 16) == checksum(result)); }
        return result;
    }

    int checksum(byte[] data) throws IOException {
        if (!iot) { return i32(data, 0) + i32(data, 4) + i32(data, 8) + i32(data, 12) + i32(data, 20); }
        int value = (i32(data, 20) & 0xffffff) ^ i32(data, 0) ^ i32(data, 4) ^ i32(data, 8) ^ i32(data, 12);
        for (int offset = 24, end = 24 + encryptedBodyLength(data); offset + 4 <= end; offset += 4) {
            value ^= i32(data, offset);
        }
        return value;
    }

    private void transform(byte[] data, boolean encrypt) throws IOException {
        int mode = mode(data);
        require(mode <= 2);
        if (mode == 0) { return; }
        if (!encrypt) {
            if (iot) { mixIdentity(data); }
            identity.decrypt(data, 4);
        }
        byte[] key = new byte[8];
        System.arraycopy(data, 0, key, 0, 4);
        System.arraycopy(data, 20, key, 4, iot ? 3 : 4);
        try (var bootstrap = new YooseeRc5(8, 6, key)) {
            var cipher = mode == 1 ? bootstrap : negotiated.orElseThrow(() -> new IOException("Unnegotiated Yoosee key mode."));
            if (encrypt) { cipher.encrypt(data, 12); } else { cipher.decrypt(data, 12); }
            for (int offset = 24, end = 24 + encryptedBodyLength(data); offset + 8 <= end; offset += 8) {
                if (encrypt) { cipher.encrypt(data, offset); } else { cipher.decrypt(data, offset); }
            }
        } finally { Arrays.fill(key, (byte) 0); }
        if (encrypt) {
            identity.encrypt(data, 4);
            if (iot) { mixIdentity(data); }
        }
    }

    private static void mixIdentity(byte[] data) {
        buffer(data).putInt(4, i32(data, 4) ^ i32(data, 12)).putInt(8, i32(data, 8) ^ i32(data, 16));
    }

    private int encryptedBodyLength(byte[] data) throws IOException {
        int length = data.length - 24;
        if (iot) {
            if ((flags(data) & (1 << 22)) != 0) { length -= 80; }
            if ((flags(data) & (1 << 24)) != 0) { length -= 16; }
        }
        require(length >= 0);
        return length;
    }

    private void validate(byte[] data) throws IOException {
        require(data != null && data.length >= 24 && data.length <= 65535 && u16(data, 2) == data.length);
        require(data[0] == 0x7f || (iot && data[0] == 0x7e));
        encryptedBodyLength(data);
    }
    @Override public void close() { identity.close(); negotiated.ifPresent(YooseeRc5::close); negotiated = Optional.empty(); }
}
