package com.ivanocj.cameras.availability.infrastructure.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class PpcsPacketTest {
    @Test
    void encodesTheHelloDatagramFromTheCImplementation() {
        assertArrayEquals(HexFormat.of().parseHex("f1000000"), new PpcsPacket(0, new byte[0]).encode());
    }

    @Test
    void decodesAnUnsignedMessageType() {
        assertEquals(0xe1, PpcsPacket.decode(HexFormat.of().parseHex("f1e10000")).type());
    }

    @Test
    void rejectsAFalsePayloadLength() {
        assertThrows(IllegalArgumentException.class,
                () -> PpcsPacket.decode(HexFormat.of().parseHex("f1410014aabb")));
    }

    @Test
    void rejectsNonPpcsMagic() {
        assertThrows(IllegalArgumentException.class,
                () -> PpcsPacket.decode(HexFormat.of().parseHex("00e10000")));
    }

    @Test
    void rejectsTruncatedHeadersAsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> PpcsPacket.decode(new byte[0]));
    }
}
