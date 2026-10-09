package com.ivanocj.cameras.availability.infrastructure.protocol;

import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import static com.ivanocj.cameras.availability.infrastructure.protocol.YooseeWire.*;

/** Bounded, session-owned 0x70 reassembly. Does not reuse the native SDK's unsafe global buffers. */
public final class YooseeFragments implements AutoCloseable {
    private final boolean iot;
    private final Map<Key, Assembly> pending = new HashMap<>();
    private boolean closed;
    private record Key(long identity, int sequence) { }
    public record Received(byte[] acknowledgement, Optional<byte[]> complete) { }
    private static final class Assembly {
        final byte[] data;
        final boolean[] received;
        final int chunk;
        int count;
        Assembly(int size, int count, int chunk) { data = new byte[size]; received = new boolean[count]; this.chunk = chunk; }
    }

    public YooseeFragments(TransportProtocol protocol) {
        if (protocol.legacyUdp()) { throw new IllegalArgumentException("GUTE fragments are not PPCS/XQ frames."); }
        iot = protocol == TransportProtocol.IOTVIDEO;
    }

    public Received receive(byte[] wire) throws IOException {
        if (closed) { throw new IllegalStateException("Yoosee fragment receiver is closed."); }
        require(wire.length >= 24 && wire.length == u16(wire, 2) && wire[0] == 0x70 && wire[1] == 1);
        var data = wire.clone();
        int random = u16(data, 20), check = random;
        for (int i = 4; i < 20; i += 2) {
            int decoded = u16(data, i) ^ random;
            buffer(data).putShort(i, (short) decoded);
            check |= decoded;
        }
        require(check == u16(data, 22));
        int total = u16(data, 16), index = data[19] & 255;
        int chunk = iot ? (data[18] & 128) == 0 ? IotVideoRemoteMessages.MTU - 24 : 1392 : 1128;
        int count = (total + chunk - 1) / chunk;
        require(total >= 24 && total <= 32791 && count > 0 && (data[18] & 127) == count && index < count);
        int length = Math.min(chunk, total - index * chunk);
        require(data.length == 24 + length);
        var key = new Key(i64(data, 4), i32(data, 12));
        if (!pending.containsKey(key)) {
            require(pending.size() < 4);
            pending.put(key, new Assembly(total, count, chunk));
        }
        var assembly = pending.get(key);
        require(assembly.data.length == total && assembly.received.length == count && assembly.chunk == chunk);
        int offset = index * chunk;
        if (assembly.received[index]) {
            require(Arrays.equals(assembly.data, offset, offset + length, data, 24, data.length));
        } else {
            System.arraycopy(data, 24, assembly.data, offset, length);
            assembly.received[index] = true;
            assembly.count++;
        }
        var ack = Arrays.copyOf(data, 24);
        ack[1] = 2;
        buffer(ack).putShort(2, (short) 24);
        for (int i = 4; i < 20; i += 2) { buffer(ack).putShort(i, (short) (u16(ack, i) ^ random)); }
        if (assembly.count == count) {
            require(assembly.data.length == u16(assembly.data, 2));
            pending.remove(key);
            return new Received(ack, Optional.of(assembly.data));
        }
        return new Received(ack, Optional.empty());
    }
    @Override public void close() {
        pending.values().forEach(value -> Arrays.fill(value.data, (byte) 0));
        pending.clear();
        closed = true;
    }
}
