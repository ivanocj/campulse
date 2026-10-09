package com.ivanocj.cameras.availability.infrastructure.protocol;

import com.ivanocj.cameras.availability.application.model.GwellLanDevice;
import com.ivanocj.cameras.availability.domain.model.GwellDeviceId;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalInt;

/** LocalNetGDevInfo's UDP-8899 protocol. This is not F1/PPCS framing or the encrypted remote protocol. */
public final class GwellDiscoveryMessages {
    public static final int PORT = 8899;
    private static final int HEADER_SIZE = 28;
    private static final int MAX_SIZE = 1024;
    private static final int FLAG_CONTROLLED_TAIL = 1 << 6;

    private GwellDiscoveryMessages() {
    }

    /** The recovered sender transmits the entire zero-padded buffer despite a 28-byte header length. */
    public static byte[] searchRequest() {
        return ByteBuffer.allocate(MAX_SIZE).order(ByteOrder.BIG_ENDIAN).putInt(1).putInt(0).putInt(HEADER_SIZE).array();
    }

    public static GwellLanDevice readReply(byte[] bytes, InetSocketAddress source) {
        require(bytes, HEADER_SIZE);
        if (bytes.length > MAX_SIZE) {
            throw new IllegalArgumentException("Oversized GWell discovery reply.");
        }
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int declared = buffer.getInt(8);
        if (buffer.getInt(0) != 2 || buffer.getInt(4) != 0 || declared < HEADER_SIZE || declared > bytes.length) {
            throw new IllegalArgumentException("Invalid GWell discovery reply header.");
        }
        int flags = buffer.getInt(12);
        var identifier = GwellDeviceId.fromWire(buffer.getInt(16));
        boolean controlled = (flags & FLAG_CONTROLLED_TAIL) != 0;
        if (!controlled) {
            // Legacy replies expose the fixed tail through byte 91; the word at 88 controls the last field.
            require(bytes, 92);
        }
        var model = OptionalInt.empty();
        if ((flags & 1) != 0) {
            require(bytes, 72);
            model = OptionalInt.of(buffer.getInt(68));
        }
        if (controlled && (flags & (1 << 4)) != 0) {
            require(bytes, 84);
        }
        if (!controlled || (flags & (1 << 7)) != 0) {
            require(bytes, 92);
            if (buffer.getInt(88) == 1) {
                require(bytes, 96);
            }
        }
        var mac = Optional.<String>empty();
        if (!controlled || (flags & (1 << 8)) != 0) {
            require(bytes, 68);
            // The app reverses the two-byte and four-byte MAC words independently.
            byte[] address = {bytes[61], bytes[60], bytes[67], bytes[66], bytes[65], bytes[64]};
            mac = Optional.of(HexFormat.ofDelimiter(":").formatHex(address));
        }
        return new GwellLanDevice(identifier, source, buffer.getInt(20), buffer.getInt(24), flags, mac, model);
    }

    private static void require(byte[] bytes, int length) {
        if (bytes.length < length) {
            throw new IllegalArgumentException("Truncated GWell discovery reply.");
        }
    }
}
