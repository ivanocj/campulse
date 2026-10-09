package com.ivanocj.cameras.availability.infrastructure.probe;

import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.infrastructure.protocol.GuteFrameCodec;
import com.ivanocj.cameras.availability.infrastructure.protocol.YooseeFragments;
import com.ivanocj.cameras.availability.infrastructure.protocol.YooseeQuickLz;
import com.ivanocj.cameras.availability.infrastructure.transport.YooseeDeadline;
import com.ivanocj.cameras.availability.infrastructure.transport.YooseeTcpTransport;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import static com.ivanocj.cameras.availability.infrastructure.protocol.YooseeWire.*;

/** Request/response correlation and delivery ACKs. ACK success is never device-presence evidence. */
final class GuteTcpSession implements AutoCloseable {
    static final class AuthenticationFailed extends IOException {
        AuthenticationFailed() { super("Yoosee account was rejected or superseded; refresh authorized credentials."); }
    }
    private final TransportProtocol protocol;
    private final YooseeTcpTransport transport;
    private final YooseeDeadline deadline;
    private final YooseeFragments fragments;
    private final GuteFrameCodec codec;
    private final Runnable onResponse;
    private long identity;
    private int inbound;

    GuteTcpSession(TransportProtocol protocol, long identity, YooseeTcpTransport transport, YooseeDeadline deadline,
                   GuteFrameCodec codec, Runnable onResponse) {
        this.protocol = protocol;
        this.identity = identity;
        this.transport = transport;
        this.deadline = deadline;
        this.codec = codec;
        this.onResponse = onResponse;
        fragments = new YooseeFragments(protocol);
    }
    void sessionIdentity(long identity) throws IOException { require(identity != 0); this.identity = identity; }

    byte[] exchange(byte[] plain, boolean response) throws IOException {
        byte[] wire = {};
        try {
            wire = codec.encode(plain);
            return exchangeEncoded(wire, plain[1] & 255, i32(plain, 12), response);
        }
        finally { Arrays.fill(wire, (byte) 0); Arrays.fill(plain, (byte) 0); }
    }

    byte[] exchangeEncoded(byte[] request, int command, int sequence, boolean response) throws IOException {
        var phase = deadline.phase(Duration.ofSeconds(5));
        int sent = 0;
        boolean acknowledged = false;
        int expectedMode = codec.mode(request);
        long retry = 0;
        while (true) {
            phase.check();
            if (!acknowledged && sent < 3 && (sent == 0 || System.nanoTime() - retry >= 0)) {
                transport.send(request, phase);
                sent++;
                retry = System.nanoTime() + 750_000_000L;
            }
            var received = transport.receive(phase);
            if (received.isEmpty()) { continue; }
            require(++inbound <= 4096);
            var wire = received.orElseThrow();
            if (wire[0] == 0x70) {
                // Our small requests never fragment; fragment ACKs do not affect correlation or readiness.
                if (wire[1] == 2) { continue; }
                var assembled = fragments.receive(wire);
                transport.send(assembled.acknowledgement(), phase);
                if (assembled.complete().isEmpty()) { continue; }
                wire = assembled.complete().orElseThrow();
            }
            var frame = codec.decode(wire);
            require(codec.mode(frame) != 0);
            if (i64(frame, 4) != identity) { continue; }
            if (protocol == TransportProtocol.IOTVIDEO) { frame = YooseeQuickLz.unpackFrame(frame); }
            boolean isResponse = codec.response(frame);
            boolean isAck = codec.ack(frame);
            require(!(isAck && isResponse));
            boolean expectedEnvelope = frame[0] == request[0] && codec.mode(frame) == expectedMode;
            if (expectedEnvelope && isAck && (frame[1] & 255) == command && i32(frame, 12) == sequence) {
                onResponse.run();
                int code = protocol == TransportProtocol.GWELL ? u16(frame, 22)
                        : frame.length >= 28 ? u16(frame, 26) : -1;
                // Only the initial GWell init ACK may request certification, not claim a ready client.
                if (code == 1 && protocol == TransportProtocol.GWELL && command == 0x81) { return frame; }
                require(code >= 0);
                if (code != 0) {
                    boolean registration = protocol == TransportProtocol.GWELL
                            ? command == 0x81 || command == 3 || command == 5 : command == 0x0c || command == 0xa6;
                    if (registration) { throw new AuthenticationFailed(); }
                    throw new IOException("Yoosee server declined the presence command; camera presence is unknown.");
                }
                acknowledged = true;
                if (!response) { return frame; }
                continue;
            }
            boolean matched = expectedEnvelope && isResponse && (frame[1] & 255) == command + 1 && i32(frame, 16) == sequence;
            if (codec.qos(frame) != 0 && !isAck) {
                // Acknowledge only valid identity-owned traffic. Never confuse its sequence with our request.
                int mode = codec.mode(frame);
                var ack = frame(frame[0] & 255, frame[1] & 255, protocol == TransportProtocol.GWELL ? 24 : 32,
                        identity, i32(frame, 12), protocol == TransportProtocol.GWELL ? mode | 16 : (mode << 16) | (1 << 20));
                if (protocol == TransportProtocol.IOTVIDEO) { buffer(ack).putShort(24, (short) 4); }
                transport.send(codec.encode(ack), phase);
            }
            if (matched && response) { onResponse.run(); return frame; }
        }
    }
    @Override public void close() { fragments.close(); }
}
