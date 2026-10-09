package com.ivanocj.cameras.availability.infrastructure.transport;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.ivanocj.cameras.availability.infrastructure.protocol.YooseeWire.*;

class YooseeTcpTransportTest {
    @Test void buffersSplitHeadersAndSeparatesCoalescedFrames() throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var deadline = new YooseeDeadline(Duration.ofSeconds(2), () -> false);
            try (var client = new YooseeTcpTransport(new InetSocketAddress(server.getInetAddress(), server.getLocalPort()), deadline, 1500);
                 var peer = server.accept()) {
                var first = frame(0x7f, 1, 24, 1, 2, 1);
                var second = frame(0x7f, 2, 32, 1, 3, 1);
                peer.getOutputStream().write(first, 0, 3);
                assertTrue(client.receive(deadline).isEmpty());
                var tail = new byte[first.length - 3 + second.length];
                System.arraycopy(first, 3, tail, 0, first.length - 3);
                System.arraycopy(second, 0, tail, first.length - 3, second.length);
                peer.getOutputStream().write(tail);
                assertArrayEquals(first, receive(client, deadline));
                assertArrayEquals(second, receive(client, deadline));
                client.send(second, deadline);
                assertArrayEquals(second, peer.getInputStream().readNBytes(second.length));
            }
        }
    }

    @Test void rejectsOversizeOrShortDeclaredLengthsBeforeWaitingForABody() throws Exception {
        for (int length : new int[]{0, 23, 1501, 65535}) {
            try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                var deadline = new YooseeDeadline(Duration.ofSeconds(2), () -> false);
                try (var client = new YooseeTcpTransport(new InetSocketAddress(server.getInetAddress(), server.getLocalPort()), deadline, 1500);
                     var peer = server.accept()) {
                    var header = new byte[]{0x7f, 1, (byte) length, (byte) (length >>> 8)};
                    peer.getOutputStream().write(header);
                    assertThrows(IOException.class, () -> receive(client, deadline));
                }
            }
        }
    }

    @Test void cancelledPartialReadsAndPeerDisconnectsNeverBecomeFrames() throws Exception {
        var cancelled = new AtomicBoolean();
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var deadline = new YooseeDeadline(Duration.ofSeconds(2), cancelled::get);
            try (var client = new YooseeTcpTransport(new InetSocketAddress(server.getInetAddress(), server.getLocalPort()), deadline, 1500);
                 var peer = server.accept()) {
                peer.getOutputStream().write(new byte[]{0x7f, 1});
                assertTrue(client.receive(deadline).isEmpty());
                cancelled.set(true);
                assertThrows(java.io.InterruptedIOException.class, () -> client.receive(deadline));
                cancelled.set(false);
                peer.shutdownOutput();
                assertThrows(EOFException.class, () -> receive(client, deadline));
            }
        }
    }

    private static byte[] receive(YooseeTcpTransport client, YooseeDeadline deadline) throws IOException {
        Optional<byte[]> received;
        do { received = client.receive(deadline); } while (received.isEmpty());
        return received.orElseThrow();
    }
}
