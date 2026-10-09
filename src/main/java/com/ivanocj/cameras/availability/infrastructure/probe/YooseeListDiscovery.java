package com.ivanocj.cameras.availability.infrastructure.probe;

import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.infrastructure.protocol.GuteFrameCodec;
import com.ivanocj.cameras.availability.infrastructure.protocol.YooseeQuickLz;
import com.ivanocj.cameras.availability.infrastructure.transport.YooseeDeadline;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static com.ivanocj.cameras.availability.infrastructure.protocol.YooseeWire.*;

/** Only solicited, identity-matched list replies can provide TCP infrastructure endpoints. */
final class YooseeListDiscovery {
    record Reply(InetSocketAddress listServer, List<InetSocketAddress> tcpServers) { }
    private YooseeListDiscovery() { }

    static Reply discover(List<InetSocketAddress> servers, byte[] plain, long identity, TransportProtocol protocol,
                          GuteFrameCodec codec, YooseeDeadline deadline) throws IOException {
        try (var socket = DatagramChannel.open(StandardProtocolFamily.INET); var selector = Selector.open()) {
            socket.configureBlocking(false);
            socket.bind(new InetSocketAddress(0));
            socket.register(selector, SelectionKey.OP_READ);
            var request = codec.encode(plain);
            var incoming = ByteBuffer.allocate(65536); // Detect actual UDP lengths instead of accepting a truncated prefix.
            long retry = 0;
            int sent = 0, received = 0;
            try {
                while (true) {
                    deadline.check();
                    if (sent < 3 && (sent == 0 || System.nanoTime() - retry >= 0)) {
                        for (var server : servers) { deadline.check(); socket.send(ByteBuffer.wrap(request), server); }
                        sent++;
                        retry = System.nanoTime() + 750_000_000L;
                    }
                    selector.select(deadline.pollMillis());
                    selector.selectedKeys().clear();
                    incoming.clear();
                    var address = socket.receive(incoming);
                    if (address == null) { continue; }
                    require(++received <= 4096);
                    if (!(address instanceof InetSocketAddress source) || !servers.contains(source)) { continue; }
                    if (incoming.position() > 10240) { continue; }
                    var bytes = Arrays.copyOf(incoming.array(), incoming.position());
                    try {
                        var frame = codec.decode(bytes);
                        require(codec.mode(frame) == 1 && i64(frame, 4) == identity);
                        if (protocol == TransportProtocol.IOTVIDEO) { frame = YooseeQuickLz.unpackFrame(frame); }
                        var referrals = endpoints(frame, source, protocol);
                        if (!referrals.isEmpty()) { return new Reply(source, referrals); }
                    } catch (IOException invalid) { /* Ignore malformed/unmatched datagrams, within the same bound. */ }
                }
            } finally { Arrays.fill(request, (byte) 0); Arrays.fill(incoming.array(), (byte) 0); }
        }
    }

    static List<InetSocketAddress> endpoints(byte[] frame, InetSocketAddress source, TransportProtocol protocol) throws IOException {
        boolean iot = protocol == TransportProtocol.IOTVIDEO;
        require(frame.length >= (iot ? 32 : 36) && (frame[1] & 255) == (iot ? 0x16 : 0x2c));
        require(u16(frame, iot ? 26 : 22) == 0);
        int count = frame[30] & 255, base = iot ? 32 : 36, stride = iot ? 36 : 12;
        require(count > 0 && base + (long) count * stride <= frame.length);
        var result = new ArrayList<InetSocketAddress>();
        for (int i = 0; i < count; i++) {
            int offset = base + i * stride;
            var ip = InetAddress.getByAddress(Arrays.copyOfRange(frame, offset, offset + 4));
            int portAt = offset + (iot ? 26 : 6);
            int port = (frame[portAt] & 255) << 8 | (frame[portAt + 1] & 255);
            if (port == 0 || !safeReferral(source.getAddress(), ip)) { continue; }
            var endpoint = new InetSocketAddress(ip, port);
            if (!result.contains(endpoint) && result.size() < 32) { result.add(endpoint); }
        }
        return List.copyOf(result);
    }

    private static boolean safeReferral(InetAddress source, InetAddress target) {
        if (!(target instanceof Inet4Address) || target.isAnyLocalAddress() || target.isMulticastAddress()
                || target.getHostAddress().equals("255.255.255.255")) { return false; }
        if (target.isLoopbackAddress()) { return source.isLoopbackAddress(); }
        if (target.isSiteLocalAddress() || target.isLinkLocalAddress()) {
            return source.isSiteLocalAddress() || source.isLinkLocalAddress() || source.isLoopbackAddress();
        }
        return true;
    }
}
