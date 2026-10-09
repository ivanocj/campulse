package com.ivanocj.cameras.availability.infrastructure.probe;

import com.ivanocj.cameras.availability.application.model.ProbeRequest;
import com.ivanocj.cameras.availability.application.model.ProbeResult;
import com.ivanocj.cameras.availability.application.model.RemotePresence;
import com.ivanocj.cameras.availability.application.model.YooseeAccount;
import com.ivanocj.cameras.availability.application.port.ProbeRunner;
import com.ivanocj.cameras.availability.domain.model.GwellDeviceId;
import com.ivanocj.cameras.availability.domain.model.IotVideoDeviceId;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import com.ivanocj.cameras.availability.infrastructure.protocol.GuteFrameCodec;
import com.ivanocj.cameras.availability.infrastructure.protocol.YooseeRc5;
import com.ivanocj.cameras.availability.testsupport.YooseeFragmentFixtures;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.RSAKeyGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.ivanocj.cameras.availability.infrastructure.protocol.YooseeWire.*;

/** Synthetic infrastructure only. Neither a native library nor a real cloud/camera is invoked. */
class YooseeRemoteLoopbackTest {
    private static final InetAddress LOOPBACK;
    private static final KeyPair RSA;
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final int GWELL_TARGET = 0x800004d2;
    private static final long IOT_TARGET = 1234;
    private static final long IOT_SESSION = 0x1234567812345678L;
    private static final String TID = "000102030405060708090a0b0c0d0e0f";
    static {
        try {
            LOOPBACK = InetAddress.getByName("127.0.0.1");
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(new RSAKeyGenParameterSpec(1024, BigInteger.valueOf(3)));
            RSA = generator.generateKeyPair();
        } catch (Exception failed) { throw new ExceptionInInitializerError(failed); }
    }
    private enum Reply { ONLINE, OFFLINE, STALE, UNKNOWN, AUTH_FAILURE, ACK_ONLY, WRONG_SEQUENCE, WRONG_TARGET, BAD_COUNTS, STALL,
        FRAGMENTED, COMPRESSED, WRONG_IDENTITY, DOWNGRADED, BAD_LENGTH, QUERY_REJECTED }

    @ParameterizedTest @EnumSource(value = TransportProtocol.class, names = {"GWELL", "IOTVIDEO"})
    void completeRegistrationAndQueryReportsPresenceButNeverCameraReachability(TransportProtocol protocol) throws Exception {
        try (var fixture = new Fixture(protocol, Reply.ONLINE)) {
            var result = fixture.runner().check(fixture.request(Duration.ofSeconds(3)), () -> false, ignored -> { });
            assertEquals(ProbeResult.Status.REPORTED_ONLINE, result.status());
            assertEquals(RemotePresence.State.ONLINE, result.remotePresence().orElseThrow().state());
            assertTrue(result.serverResponded());
            assertTrue(result.peer().isEmpty());
            assertTrue(result.publicEndpoint().isEmpty());
            assertTrue(result.p2pInformation().deviceCandidates().isEmpty());
            assertEquals(com.ivanocj.cameras.availability.application.model.P2pInformation.ConnectionMode.NONE, result.p2pInformation().mode());
            assertTrue(result.p2pInformation().localEndpoint().isPresent());
            fixture.assertPeerClosed();
        }
    }
    @ParameterizedTest @EnumSource(value = TransportProtocol.class, names = {"GWELL", "IOTVIDEO"})
    void explicitCurrentNegativePresenceIsSeparateFromTimeoutAndRejection(TransportProtocol protocol) throws Exception {
        try (var fixture = new Fixture(protocol, Reply.OFFLINE)) {
            var result = fixture.runner().check(fixture.request(Duration.ofSeconds(3)), () -> false, ignored -> { });
            assertEquals(ProbeResult.Status.REPORTED_OFFLINE, result.status());
            assertEquals(RemotePresence.State.OFFLINE, result.remotePresence().orElseThrow().state());
            fixture.assertPeerClosed();
        }
    }
    @ParameterizedTest @EnumSource(value = TransportProtocol.class, names = {"GWELL", "IOTVIDEO"})
    void aRejectedStatusCommandDoesNotInventAccountFailureOrDeviceOffline(TransportProtocol protocol) throws Exception {
        try (var fixture = new Fixture(protocol, Reply.QUERY_REJECTED)) {
            var result = fixture.runner().check(fixture.request(Duration.ofSeconds(3)), () -> false, ignored -> { });
            assertEquals(ProbeResult.Status.INCONCLUSIVE, result.status());
            assertTrue(result.remotePresence().isEmpty());
            assertTrue(result.serverResponded());
            fixture.assertPeerClosed();
        }
    }
    @ParameterizedTest @EnumSource(value = TransportProtocol.class, names = {"GWELL", "IOTVIDEO"})
    void accountRejectionIsNotDeviceOffline(TransportProtocol protocol) throws Exception {
        try (var fixture = new Fixture(protocol, Reply.AUTH_FAILURE)) {
            var result = fixture.runner().check(fixture.request(Duration.ofSeconds(3)), () -> false, ignored -> { });
            assertEquals(ProbeResult.Status.AUTH_FAILED, result.status());
            assertTrue(result.remotePresence().isEmpty());
            assertEquals(2, result.p2pInformation().respondingServers().size());
            fixture.assertPeerClosed();
        }
    }
    @Test void timestampedCachedPresenceRemainsStaleAndMissingPropertyRemainsUnknown() throws Exception {
        for (var reply : new Reply[]{Reply.STALE, Reply.UNKNOWN}) {
            try (var fixture = new Fixture(TransportProtocol.IOTVIDEO, reply)) {
                var result = fixture.runner().check(fixture.request(Duration.ofSeconds(3)), () -> false, ignored -> { });
                assertEquals(ProbeResult.Status.INCONCLUSIVE, result.status());
                assertEquals(reply == Reply.STALE ? RemotePresence.State.STALE : RemotePresence.State.UNKNOWN,
                        result.remotePresence().orElseThrow().state());
                fixture.assertPeerClosed();
            }
        }
    }
    @ParameterizedTest @EnumSource(value = TransportProtocol.class, names = {"GWELL", "IOTVIDEO"})
    void deliveryAckOrUncorrelatedResponseNeverProducesPresence(TransportProtocol protocol) throws Exception {
        for (var reply : new Reply[]{Reply.ACK_ONLY, Reply.WRONG_SEQUENCE, Reply.WRONG_TARGET, Reply.BAD_COUNTS}) {
            try (var fixture = new Fixture(protocol, reply)) {
                var result = fixture.runner().check(fixture.request(Duration.ofMillis(1200)), () -> false, ignored -> { });
                assertNotEquals(ProbeResult.Status.REPORTED_ONLINE, result.status());
                assertNotEquals(ProbeResult.Status.REPORTED_OFFLINE, result.status());
                if (protocol == TransportProtocol.GWELL && reply == Reply.WRONG_TARGET) {
                    assertEquals(RemotePresence.State.UNKNOWN, result.remotePresence().orElseThrow().state());
                } else {
                    assertTrue(result.remotePresence().isEmpty(), reply.name());
                }
                fixture.assertPeerClosed();
            }
        }
    }
    @ParameterizedTest @EnumSource(value = TransportProtocol.class, names = {"GWELL", "IOTVIDEO"})
    void cancellationDuringAuthenticationClosesTheOwnedTcpSessionPromptly(TransportProtocol protocol) throws Exception {
        try (var fixture = new Fixture(protocol, Reply.STALL)) {
            var cancelled = new AtomicBoolean();
            var task = new FutureTask<>(() -> fixture.runner().check(fixture.request(Duration.ofSeconds(10)), cancelled::get, ignored -> { }));
            Thread.ofVirtual().start(task);
            assertTrue(fixture.certificationSeen.await(2, TimeUnit.SECONDS));
            cancelled.set(true);
            assertEquals(ProbeResult.Status.CANCELLED, task.get(1, TimeUnit.SECONDS).status());
            fixture.assertPeerClosed();
        }
    }
    @ParameterizedTest @EnumSource(value = TransportProtocol.class, names = {"GWELL", "IOTVIDEO"})
    void expiredAccountsAreRejectedBeforeDnsOrSockets(TransportProtocol protocol) throws Exception {
        ProbeRunner runner = protocol == TransportProtocol.GWELL
                ? new GwellRemoteProbe(ignored -> Optional.of(gwell(Optional.of(NOW))), host -> { throw new AssertionError("No DNS"); }, CLOCK)
                : new IotVideoRemoteProbe(ignored -> Optional.of(iot(Optional.of(NOW))), host -> { throw new AssertionError("No DNS"); }, CLOCK);
        var identifier = protocol == TransportProtocol.GWELL ? new GwellDeviceId("01234") : new IotVideoDeviceId(TID);
        var result = runner.check(new ProbeRequest(identifier, List.of(InetSocketAddress.createUnresolved("unused.invalid", 51701)),
                Duration.ofSeconds(1), false, protocol), () -> false, ignored -> { });
        assertEquals(ProbeResult.Status.AUTH_FAILED, result.status());
        assertFalse(result.serverResponded());
    }
    @ParameterizedTest @EnumSource(value = TransportProtocol.class, names = {"GWELL", "IOTVIDEO"})
    void fragmentedResponsesAndIotVideoCompressionUseTheSameStrictCorrelation(TransportProtocol protocol) throws Exception {
        try (var fixture = new Fixture(protocol, Reply.FRAGMENTED)) {
            var result = fixture.runner().check(fixture.request(Duration.ofSeconds(3)), () -> false, ignored -> { });
            assertEquals(ProbeResult.Status.REPORTED_ONLINE, result.status());
            fixture.assertPeerClosed();
        }
        if (protocol == TransportProtocol.IOTVIDEO) {
            try (var fixture = new Fixture(protocol, Reply.COMPRESSED)) {
                assertEquals(ProbeResult.Status.REPORTED_ONLINE,
                        fixture.runner().check(fixture.request(Duration.ofSeconds(3)), () -> false, ignored -> { }).status());
                fixture.assertPeerClosed();
            }
        }
    }
    @ParameterizedTest @EnumSource(value = TransportProtocol.class, names = {"GWELL", "IOTVIDEO"})
    void identityMismatchEncryptionDowngradeOrBadFramingCannotConfirmPresence(TransportProtocol protocol) throws Exception {
        for (var reply : new Reply[]{Reply.WRONG_IDENTITY, Reply.DOWNGRADED, Reply.BAD_LENGTH}) {
            try (var fixture = new Fixture(protocol, reply)) {
                var result = fixture.runner().check(fixture.request(Duration.ofMillis(1200)), () -> false, ignored -> { });
                assertTrue(result.remotePresence().isEmpty(), reply.name());
                fixture.assertPeerClosed();
            }
        }
    }
    @ParameterizedTest @EnumSource(value = TransportProtocol.class, names = {"GWELL", "IOTVIDEO"})
    void sharedAccountChecksWaitWithinTheirOwnBudgetAndOwnershipIncludesCleanup(TransportProtocol protocol) throws Exception {
        var loads = new AtomicInteger();
        com.ivanocj.cameras.availability.application.port.YooseeCredentialSource credentials = ignored -> {
            loads.incrementAndGet();
            return Optional.of(protocol == TransportProtocol.GWELL ? gwell(Optional.empty()) : iot(Optional.empty()));
        };
        ProbeRunner runner = protocol == TransportProtocol.GWELL
                ? new GwellRemoteProbe(credentials, InetAddress::getAllByName, CLOCK)
                : new IotVideoRemoteProbe(credentials, InetAddress::getAllByName, CLOCK);
        var cancelFirst = new AtomicBoolean();
        try (var blocked = new Fixture(protocol, Reply.STALL)) {
            var first = new FutureTask<>(() -> runner.check(blocked.request(Duration.ofSeconds(10)), cancelFirst::get, ignored -> { }));
            Thread.ofVirtual().start(first);
            try {
                assertTrue(blocked.certificationSeen.await(2, TimeUnit.SECONDS));
                var queued = runner.check(blocked.request(Duration.ofMillis(250)), () -> false, ignored -> { });
                assertEquals(ProbeResult.Status.TIMED_OUT, queued.status());
                assertEquals(1, loads.get(), "Queued check must not register a competing account session");
                assertFalse(queued.serverResponded());
                assertTrue(queued.p2pInformation().resolvedServers().isEmpty());
            } finally {
                cancelFirst.set(true);
                assertEquals(ProbeResult.Status.CANCELLED, first.get(1, TimeUnit.SECONDS).status());
                blocked.assertPeerClosed();
            }
        }
        try (var next = new Fixture(protocol, Reply.ONLINE)) {
            assertEquals(ProbeResult.Status.REPORTED_ONLINE,
                    runner.check(next.request(Duration.ofSeconds(3)), () -> false, ignored -> { }).status());
            next.assertPeerClosed();
            assertEquals(2, loads.get());
        }
    }
    private static YooseeAccount.Gwell gwell(Optional<Instant> expiry) {
        return new YooseeAccount.Gwell(new GwellDeviceId("09999"), 11, 12, 21, 22, expiry);
    }
    private static byte[] token() {
        var data = new byte[64];
        for (int i = 0; i < data.length; i++) { data[i] = (byte) i; }
        data[0] = 1;
        return data;
    }
    private static YooseeAccount.IotVideo iot(Optional<Instant> expiry) { return new YooseeAccount.IotVideo(9999, token(), expiry); }

    private static final class Fixture implements AutoCloseable {
        final TransportProtocol protocol;
        final Reply reply;
        final DatagramSocket list = new DatagramSocket(new InetSocketAddress(LOOPBACK, 0));
        final ServerSocket tcp = new ServerSocket(0, 1, LOOPBACK);
        final CountDownLatch certificationSeen = new CountDownLatch(1);
        final AtomicBoolean peerClosed = new AtomicBoolean();
        final FutureTask<Void> server;
        volatile Optional<Socket> accepted = Optional.empty();
        final long accountId;
        int serverSequence = 100;

        Fixture(TransportProtocol protocol, Reply reply) throws Exception {
            this.protocol = protocol; this.reply = reply;
            try (var account = protocol == TransportProtocol.GWELL ? gwell(Optional.empty()) : iot(Optional.empty())) {
                accountId = account instanceof YooseeAccount.Gwell gw ? gw.userId() : ((YooseeAccount.IotVideo) account).wireId();
            }
            list.setSoTimeout(3000); tcp.setSoTimeout(3000);
            server = new FutureTask<>(() -> { serve(); return null; });
            Thread.ofVirtual().name("yoosee-loopback-fixture").start(server);
        }
        ProbeRunner runner() {
            return protocol == TransportProtocol.GWELL
                    ? new GwellRemoteProbe(ignored -> Optional.of(gwell(Optional.empty())), InetAddress::getAllByName, CLOCK)
                    : new IotVideoRemoteProbe(ignored -> Optional.of(iot(Optional.empty())), InetAddress::getAllByName, CLOCK);
        }
        ProbeRequest request(Duration timeout) {
            var id = protocol == TransportProtocol.GWELL ? new GwellDeviceId("01234") : new IotVideoDeviceId(TID);
            return new ProbeRequest(id, List.of(new InetSocketAddress(LOOPBACK, list.getLocalPort())), timeout, false, protocol);
        }
        void serve() throws Exception {
            try (var codec = new GuteFrameCodec(protocol)) {
                var datagram = new DatagramPacket(new byte[2048], 2048);
                list.receive(datagram);
                var request = codec.decode(Arrays.copyOf(datagram.getData(), datagram.getLength()));
                assertEquals(accountId, i64(request, 4));
                assertEquals(protocol == TransportProtocol.GWELL ? 36 : 40, request.length);
                var response = frame(0x7f, protocol == TransportProtocol.GWELL ? 0x2c : 0x16,
                        protocol == TransportProtocol.GWELL ? 48 : 68, accountId, 0, protocol == TransportProtocol.GWELL ? 1 : 1 << 16);
                response[30] = 1;
                int base = protocol == TransportProtocol.GWELL ? 36 : 32;
                System.arraycopy(LOOPBACK.getAddress(), 0, response, base, 4);
                int portAt = base + (protocol == TransportProtocol.GWELL ? 6 : 26);
                response[portAt] = (byte) (tcp.getLocalPort() >>> 8); response[portAt + 1] = (byte) tcp.getLocalPort();
                var encoded = codec.encode(response);
                list.send(new DatagramPacket(encoded, encoded.length, datagram.getSocketAddress()));
                try (var socket = tcp.accept()) {
                    accepted = Optional.of(socket);
                    socket.setSoTimeout(3000);
                    if (protocol == TransportProtocol.GWELL) { gwellSession(socket, codec); }
                    else { iotSession(socket, codec); }
                }
            }
        }
        void gwellSession(Socket socket, GuteFrameCodec codec) throws Exception {
            var init = readRequest(socket, codec, 0x81);
            assertEquals(80, init.length, "Native mode-1 init appends both verification words");
            assertEquals(1, init[21] & 1);
            assertEquals(21, i32(init, 72)); assertEquals(22, i32(init, 76));
            write(socket, codec.encode(ack(init, 1)));
            var cert = readRequest(socket, codec, 3);
            certificationSeen.countDown();
            assertEquals(21, i32(cert, 24)); assertEquals(22, i32(cert, 28));
            assertEquals(11, i32(cert, 32)); assertEquals(12, i32(cert, 36));
            if (reply == Reply.STALL) { untilClosed(socket); return; }
            if (reply == Reply.AUTH_FAILURE) { write(socket, codec.encode(ack(cert, 4))); untilClosed(socket); return; }
            var rsa = (java.security.interfaces.RSAPublicKey) RSA.getPublic();
            var certified = response(cert, 160, 1);
            buffer(certified).putInt(24, 123).putShort(28, (short) 128).putShort(30, (short) 1024);
            var modulus = rsa.getModulus().toByteArray();
            System.arraycopy(modulus, modulus.length - 128, certified, 32, 128);
            write(socket, codec.encode(certified));
            var update = readRequest(socket, codec, 5);
            assertEquals(170, update.length, "Native mode-1 key update also appends both verification words");
            assertEquals(1, update[21] & 1);
            assertEquals(21, i32(update, 162)); assertEquals(22, i32(update, 166));
            assertEquals(128, u16(update, 32)); assertEquals(123, i32(update, 24));
            var decrypt = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            decrypt.init(Cipher.DECRYPT_MODE, RSA.getPrivate());
            var key = decrypt.doFinal(update, 34, 128);
            assertEquals(hash(key), i32(update, 28));
            assertEquals((int) (NOW.getEpochSecond() / 7200), i32(key, 4));
            assertEquals(accountId, Integer.toUnsignedLong(i32(key, 8)));
            assertEquals(21, i32(key, 16)); assertEquals(22, i32(key, 20));
            write(socket, codec.encode(response(update, 24, 1)));
            codec.negotiate(key); Arrays.fill(key, (byte) 0);
            init = readRequest(socket, codec, 0x81);
            assertEquals(2, codec.mode(init));
            assertEquals(72, init.length, "Negotiated mode does not use a verification trailer");
            write(socket, codec.encode(ack(init, 0)));
            var query = readRequest(socket, codec, 0x2d);
            assertEquals(GWELL_TARGET, i32(query, 32)); assertEquals(1, u16(query, 30));
            if (reply == Reply.QUERY_REJECTED) { write(socket, codec.encode(ack(query, 5))); untilClosed(socket); return; }
            if (reply == Reply.ACK_ONLY) { write(socket, codec.encode(ack(query, 0))); untilClosed(socket); return; }
            int count = reply == Reply.FRAGMENTED ? 30 : 1;
            var presence = response(query, 32 + 52 * count, 2);
            buffer(presence).putShort(28, (short) count).putInt(32, reply == Reply.WRONG_TARGET ? 99 : GWELL_TARGET);
            for (int i = 1; i < count; i++) { buffer(presence).putInt(32 + 52 * i, 100000 + i); presence[40 + 52 * i] = 2; }
            presence[40] = (byte) (reply == Reply.OFFLINE ? 0 : 2);
            if (reply == Reply.WRONG_SEQUENCE) { buffer(presence).putInt(16, i32(query, 12) + 1); }
            if (reply == Reply.BAD_COUNTS) { buffer(presence).putShort(28, (short) 2); }
            writePresence(socket, codec, presence);
            untilClosed(socket);
        }
        void iotSession(Socket socket, GuteFrameCodec codec) throws Exception {
            var cert = readRequest(socket, codec, 0x0c);
            certificationSeen.countDown();
            assertEquals(164, cert.length);
            assertEquals(1, u16(cert, 24));
            assertEquals(1032, i32(cert, 64));
            if (reply == Reply.STALL) { untilClosed(socket); return; }
            if (reply == Reply.AUTH_FAILURE) { write(socket, codec.encode(ack(cert, 4))); untilClosed(socket); return; }
            var auth = Arrays.copyOfRange(cert, 84, 164);
            var aes = Cipher.getInstance("AES/CBC/NoPadding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(nonceKey(Arrays.copyOfRange(auth, 4, 16)), "AES"),
                    new IvParameterSpec(Arrays.copyOf("iotVideo".getBytes(StandardCharsets.US_ASCII), 16)));
            System.arraycopy(aes.doFinal(auth, 16, 48), 0, auth, 16, 48);
            assertArrayEquals(Arrays.copyOf(token(), 48), Arrays.copyOfRange(auth, 16, 64));
            int sum = 0;
            for (int i = 4; i < 64; i += 2) { sum += u16(auth, i); }
            assertEquals(sum & 65535, u16(auth, 2));
            var signed = buffer(new byte[72]).putInt(i32(cert, 12)).putInt(i32(cert, 16)).array();
            System.arraycopy(auth, 0, signed, 8, 64);
            var tokenKey = Arrays.copyOfRange(token(), 48, 64);
            var hmac = Mac.getInstance("HmacMD5"); hmac.init(new SecretKeySpec(tokenKey, "HmacMD5"));
            assertArrayEquals(hmac.doFinal(signed), Arrays.copyOfRange(auth, 64, 80));
            var key = Arrays.copyOfRange(cert, 32, 64);
            try (var rc5 = new YooseeRc5(16, 6, tokenKey)) { rc5.decrypt(key, 0); rc5.decrypt(key, 16); }
            assertEquals(hash(key), i32(cert, 28));
            var certified = response(cert, 36, 1);
            buffer(certified).putLong(28, IOT_SESSION);
            write(socket, codec.encode(certified));
            codec.negotiate(key); Arrays.fill(key, (byte) 0);
            var init = readRequest(socket, codec, 0xa6);
            assertEquals(IOT_SESSION, i64(init, 4)); assertEquals(0x7e, init[0]);
            assertEquals(62, init.length); assertEquals(62, u16(init, 24));
            var registered = response(init, 60, 2);
            buffer(registered).putShort(24, (short) 1).putShort(28, (short) 1).putLong(32, IOT_TARGET);
            System.arraycopy(HexFormat.of().parseHex(TID), 0, registered, 44, 16);
            write(socket, codec.encode(registered));
            var query = readRequest(socket, codec, 0xb7);
            assertEquals(IOT_TARGET, i64(query, 24)); assertEquals(0, u16(query, 36)); assertEquals(0, query[38]);
            if (reply == Reply.QUERY_REJECTED) { write(socket, codec.encode(ack(query, 5))); untilClosed(socket); return; }
            if (reply == Reply.ACK_ONLY) { write(socket, codec.encode(ack(query, 0))); untilClosed(socket); return; }
            long stamp = NOW.getEpochSecond() - (reply == Reply.STALE ? 121 : 0);
            String json = reply == Reply.UNKNOWN ? "{}" : "{\"ProReadonly\":{\"_online\":{\"stVal\":"
                    + (reply == Reply.OFFLINE ? 0 : 1) + ",\"t\":" + stamp + "}}}";
            if (reply == Reply.FRAGMENTED) { json += " ".repeat(2200); }
            var payload = json.getBytes(StandardCharsets.UTF_8);
            var presence = response(query, 41 + payload.length, 2);
            buffer(presence).putLong(24, reply == Reply.WRONG_TARGET ? 99 : IOT_TARGET).putShort(38, (short) payload.length);
            presence[32] = 1;
            System.arraycopy(payload, 0, presence, 40, payload.length);
            if (reply == Reply.WRONG_SEQUENCE) { buffer(presence).putInt(16, i32(query, 12) + 1); }
            if (reply == Reply.BAD_COUNTS) { buffer(presence).putShort(38, (short) (payload.length + 1)); }
            writePresence(socket, codec, presence);
            untilClosed(socket);
        }
        void writePresence(Socket socket, GuteFrameCodec codec, byte[] presence) throws IOException {
            if (reply == Reply.WRONG_IDENTITY) { buffer(presence).putLong(4, 99); }
            if (reply == Reply.DOWNGRADED) {
                buffer(presence).putInt(20, protocol == TransportProtocol.GWELL ? 1 | 32 | 4 : (1 << 16) | (1 << 21) | (1 << 18));
            }
            if (reply == Reply.COMPRESSED) {
                int originalLength = presence.length - 24;
                var stored = new byte[9 + originalLength];
                stored[0] = 0x5a; // Native level 2, four-byte lengths, stored (uncompressed) QuickLZ block.
                buffer(stored).putInt(1, stored.length).putInt(5, originalLength);
                System.arraycopy(presence, 24, stored, 9, originalLength);
                presence = Arrays.copyOf(presence, 24 + stored.length);
                buffer(presence).putShort(2, (short) presence.length).putInt(20, i32(presence, 20) | 1 | originalLength << 1);
                System.arraycopy(stored, 0, presence, 24, stored.length);
            }
            var wire = codec.encode(presence);
            if (reply == Reply.BAD_LENGTH) {
                buffer(wire).putShort(2, (short) 65535);
                write(socket, Arrays.copyOf(wire, 4));
            } else if (reply == Reply.FRAGMENTED) {
                int chunk = protocol == TransportProtocol.GWELL ? 1128 : 1008;
                int count = (wire.length + chunk - 1) / chunk;
                for (int i = count - 1; i >= 0; i--) {
                    var part = YooseeFragmentFixtures.fragment(wire, accountId, i32(presence, 12), chunk, i, false);
                    write(socket, part);
                    if (i == count - 1) { write(socket, part); }
                }
            } else { write(socket, wire); }
        }
        byte[] response(byte[] request, int length, int mode) {
            var result = frame(request[0] & 255, (request[1] & 255) + 1, length, i64(request, 4), ++serverSequence,
                    protocol == TransportProtocol.GWELL ? mode | 32 | 4 : (mode << 16) | (1 << 21) | (1 << 18));
            buffer(result).putInt(16, i32(request, 12));
            return result;
        }
        byte[] ack(byte[] request, int code) {
            int mode = protocol == TransportProtocol.GWELL ? request[20] & 3 : i32(request, 20) >>> 16 & 3;
            var result = frame(request[0] & 255, request[1] & 255, protocol == TransportProtocol.GWELL ? 24 : 32,
                    i64(request, 4), i32(request, 12), protocol == TransportProtocol.GWELL ? mode | 16 : (mode << 16) | (1 << 20));
            buffer(result).putShort(protocol == TransportProtocol.GWELL ? 22 : 26, (short) code);
            return result;
        }
        static byte[] readRequest(Socket socket, GuteFrameCodec codec, int command) throws Exception {
            while (true) {
                var header = socket.getInputStream().readNBytes(4);
                if (header.length != 4) { throw new EOFException(); }
                int length = u16(header, 2);
                assertTrue(length >= 24 && length <= 10240);
                var data = Arrays.copyOf(header, length);
                var body = socket.getInputStream().readNBytes(length - 4);
                assertEquals(length - 4, body.length);
                System.arraycopy(body, 0, data, 4, body.length);
                var plain = codec.decode(data);
                if (codec.ack(plain)) { continue; }
                assertEquals(command, plain[1] & 255);
                return plain;
            }
        }
        static void write(Socket socket, byte[] data) throws IOException {
            // Split the frame header itself; TCP reads must not assume datagram boundaries.
            socket.getOutputStream().write(data, 0, 3); socket.getOutputStream().flush();
            socket.getOutputStream().write(data, 3, data.length - 3); socket.getOutputStream().flush();
        }
        void untilClosed(Socket socket) throws IOException {
            var input = socket.getInputStream();
            while (input.read() != -1) { /* Drain delivery ACKs and bounded retries, but never answer the query again. */ }
            peerClosed.set(true);
        }
        void assertPeerClosed() throws Exception { server.get(3, TimeUnit.SECONDS); assertTrue(peerClosed.get()); }
        @Override public void close() throws Exception {
            list.close(); tcp.close();
            if (accepted.isPresent()) { accepted.orElseThrow().close(); }
            if (!server.isDone()) { server.cancel(true); }
        }
        private static byte[] nonceKey(byte[] nonce) {
            var values = new int[]{0, 0x4e67c6a7, 0x1505, 0};
            for (byte octet : nonce) {
                int b = octet & 255;
                values[0] = values[0] * 131 + b;
                values[1] ^= b + values[1] * 32 + (values[1] >>> 2);
                values[2] = values[2] * 33 + b;
                values[3] = values[3] * 65599 + b;
            }
            return buffer(new byte[16]).putInt(values[0]).putInt(values[1]).putInt(values[2]).putInt(values[3]).array();
        }
    }
}
