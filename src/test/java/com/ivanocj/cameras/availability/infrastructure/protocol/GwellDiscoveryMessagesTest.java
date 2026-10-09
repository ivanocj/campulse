package com.ivanocj.cameras.availability.infrastructure.protocol;

import com.ivanocj.cameras.availability.domain.model.GwellDeviceId;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class GwellDiscoveryMessagesTest {
    private static final InetSocketAddress SOURCE = new InetSocketAddress("192.168.1.8", 8899);

    @Test
    void reproducesTheDecodedApps1024ByteBigEndianDiscoveryRequest() {
        byte[] request = GwellDiscoveryMessages.searchRequest();
        assertEquals(1024, request.length);
        assertArrayEquals(HexFormat.of().parseHex("00000001000000000000001c0000000000000000000000000000000000000000"),
                Arrays.copyOf(request, 32));
        assertTrue(Arrays.equals(new byte[992], Arrays.copyOfRange(request, 32, request.length)));
    }

    @Test
    void extractsLegacyNumericIdentityTypeSubtypeModelAndTheReversedMacWords() {
        var reply = ByteBuffer.allocate(96).putInt(2).putInt(0).putInt(28).putInt(1)
                .putInt(0x800004d2).putInt(7).putInt(3);
        reply.putShort(60, (short) 0x1100).putInt(64, 0x55443322).putInt(68, 42);
        var device = GwellDiscoveryMessages.readReply(reply.array(), SOURCE);
        assertEquals(new GwellDeviceId("01234"), device.identifier());
        assertEquals(SOURCE, device.endpoint());
        assertEquals(7, device.deviceType());
        assertEquals(3, device.subType());
        assertEquals(42, device.model().orElseThrow());
        assertEquals(Optional.of("00:11:22:33:44:55"), device.macAddress());
    }

    @Test
    void flagControlledRepliesCanOmitTailFieldsWithoutInventingPadding() {
        var reply = ByteBuffer.allocate(28).putInt(2).putInt(0).putInt(28).putInt(1 << 6)
                .putInt(1234).putInt(7).putInt(3);
        var device = GwellDiscoveryMessages.readReply(reply.array(), SOURCE);
        assertEquals("1234", device.identifier().value());
        assertTrue(device.macAddress().isEmpty());
        assertTrue(device.model().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4, 27, 28, 59, 67, 71, 83, 91})
    void truncatedLegacyRepliesAreNotExtendedToTheDecodedParsers200ByteLimit(int length) {
        var reply = ByteBuffer.allocate(96).putInt(2).putInt(0).putInt(28).putInt(0)
                .putInt(1234).putInt(7).putInt(3).array();
        assertThrows(IllegalArgumentException.class,
                () -> GwellDiscoveryMessages.readReply(Arrays.copyOf(reply, length), SOURCE));
    }

    @Test
    void eachAdvertisedExtensionRequiresItsActualBytes() {
        for (int flag : new int[] {1, 1 << 4, 1 << 7, 1 << 8}) {
            var reply = ByteBuffer.allocate(28).putInt(2).putInt(0).putInt(28).putInt((1 << 6) | flag)
                    .putInt(1234).putInt(7).putInt(3).array();
            assertThrows(IllegalArgumentException.class, () -> GwellDiscoveryMessages.readReply(reply, SOURCE));
        }
    }

    @Test
    void legacyRepliesNeedTheOptionalTailOnlyWhenItsPresenceWordIsOne() {
        var reply = ByteBuffer.allocate(96).putInt(2).putInt(0).putInt(28).putInt(0)
                .putInt(1234).putInt(7).putInt(3);
        assertEquals("1234", GwellDiscoveryMessages.readReply(Arrays.copyOf(reply.array(), 92), SOURCE).identifier().value());
        reply.putInt(88, 1);
        assertThrows(IllegalArgumentException.class,
                () -> GwellDiscoveryMessages.readReply(Arrays.copyOf(reply.array(), 95), SOURCE));
        assertEquals("1234", GwellDiscoveryMessages.readReply(reply.array(), SOURCE).identifier().value());
    }

    @Test
    void controlledExtensionLengthsMatchTheRecoveredPresenceBitsAndMacOrder() {
        int[] flags = {1, 1 << 4, 1 << 7, 1 << 8};
        int[] lengths = {72, 84, 92, 68};
        for (int index = 0; index < flags.length; index++) {
            var reply = ByteBuffer.allocate(lengths[index]).putInt(2).putInt(0).putInt(28).putInt((1 << 6) | flags[index])
                    .putInt(1234).putInt(7).putInt(3);
            var decoded = GwellDiscoveryMessages.readReply(reply.array(), SOURCE);
            assertEquals("1234", decoded.identifier().value());
            assertThrows(IllegalArgumentException.class,
                    () -> GwellDiscoveryMessages.readReply(Arrays.copyOf(reply.array(), reply.capacity() - 1), SOURCE));
            if (flags[index] == (1 << 7)) {
                reply.putInt(88, 1);
                assertThrows(IllegalArgumentException.class, () -> GwellDiscoveryMessages.readReply(reply.array(), SOURCE));
                assertEquals("1234", GwellDiscoveryMessages.readReply(Arrays.copyOf(reply.array(), 96), SOURCE).identifier().value());
            }
            if (flags[index] == (1 << 8)) {
                reply.putShort(60, (short) 0x1100).putInt(64, 0x55443322);
                assertEquals(Optional.of("00:11:22:33:44:55"),
                        GwellDiscoveryMessages.readReply(reply.array(), SOURCE).macAddress());
            }
        }
    }

    @Test
    void falseCommandsErrorsLengthsAndZeroIdentifiersDoNotCountAsDiscovery() {
        for (int[] header : new int[][] {{1, 0, 28, 1234}, {2, 1, 28, 1234}, {2, 0, 27, 1234},
                {2, 0, 200, 1234}, {2, 0, -1, 1234}, {2, 0, 28, 0}, {2, 0, 28, 0x80000000}}) {
            var reply = ByteBuffer.allocate(96).putInt(header[0]).putInt(header[1]).putInt(header[2]).putInt(0)
                    .putInt(header[3]).putInt(7).putInt(3).array();
            assertThrows(IllegalArgumentException.class, () -> GwellDiscoveryMessages.readReply(reply, SOURCE));
        }
    }
}
