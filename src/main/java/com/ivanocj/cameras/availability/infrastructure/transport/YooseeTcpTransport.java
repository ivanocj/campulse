package com.ivanocj.cameras.availability.infrastructure.transport;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Optional;
import static com.ivanocj.cameras.availability.infrastructure.protocol.YooseeWire.*;

/** Nonblocking socket with cancellable connect/read/write, fixed receive storage and fragmented TCP-frame handling. */
public final class YooseeTcpTransport implements AutoCloseable {
    private final SocketChannel channel;
    private final Selector selector;
    private final ByteBuffer incoming = ByteBuffer.allocate(10240);
    private final int maxFrame;

    public YooseeTcpTransport(InetSocketAddress endpoint, YooseeDeadline deadline, int maxFrame) throws IOException {
        this.maxFrame = maxFrame;
        selector = Selector.open();
        SocketChannel opened;
        try { opened = SocketChannel.open(); }
        catch (IOException failed) { selector.close(); throw failed; }
        channel = opened;
        try {
            channel.configureBlocking(false);
            channel.socket().setTcpNoDelay(true);
            deadline.check();
            channel.register(selector, SelectionKey.OP_CONNECT);
            if (!channel.connect(endpoint)) {
                while (!channel.finishConnect()) { waitFor(SelectionKey.OP_CONNECT, deadline); }
            }
            channel.keyFor(selector).interestOps(SelectionKey.OP_READ);
        } catch (IOException | RuntimeException failed) { close(); throw failed; }
    }

    public InetSocketAddress localEndpoint() throws IOException { return (InetSocketAddress) channel.getLocalAddress(); }

    public void send(byte[] data, YooseeDeadline deadline) throws IOException {
        var source = ByteBuffer.wrap(data);
        while (source.hasRemaining()) {
            deadline.check();
            if (channel.write(source) == 0) { waitFor(SelectionKey.OP_WRITE, deadline); }
        }
        channel.keyFor(selector).interestOps(SelectionKey.OP_READ);
    }

    /** At most one 50ms polling interval; keeps retries/cancellation independent from TCP packet boundaries. */
    public Optional<byte[]> receive(YooseeDeadline deadline) throws IOException {
        deadline.check();
        var available = extract();
        if (available.isPresent()) { return available; }
        waitFor(SelectionKey.OP_READ, deadline);
        int count = channel.read(incoming);
        if (count < 0) { throw new EOFException("Yoosee infrastructure closed the session."); }
        return extract();
    }

    private Optional<byte[]> extract() throws IOException {
        if (incoming.position() < 4) { return Optional.empty(); }
        int length = (incoming.get(2) & 255) | (incoming.get(3) & 255) << 8;
        int protocol = incoming.get(0) & 255;
        require((protocol == 0x7f || protocol == 0x7e || protocol == 0x70) && length >= 24 && length <= maxFrame);
        if (incoming.position() < length) { return Optional.empty(); }
        incoming.flip();
        var data = new byte[length];
        incoming.get(data);
        incoming.compact();
        return Optional.of(data);
    }

    private void waitFor(int operation, YooseeDeadline deadline) throws IOException {
        channel.keyFor(selector).interestOps(operation);
        selector.select(deadline.pollMillis());
        selector.selectedKeys().clear();
        deadline.check();
    }
    @Override public void close() throws IOException {
        try { channel.close(); } finally { selector.close(); java.util.Arrays.fill(incoming.array(), (byte) 0); }
    }
}
