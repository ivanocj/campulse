package com.ivanocj.cameras.availability.infrastructure.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class YooseeQuickLzTest {
    @Test void decodesLiteralAndUncompressedLevelTwoBlocks() throws Exception {
        assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), YooseeQuickLz.decompress(HexFormat.of().parseHex("58080568656c6c6f"), 5));
        assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), YooseeQuickLz.decompress(HexFormat.of().parseHex("590c050000008068656c6c6f"), 5));
    }
    @Test void rejectsLengthMismatchOutOfBoundsReferencesAndUnsupportedLevels() {
        for (var data : new String[]{"58090568656c6c6f", "5d08058000000000", "590f080100008000006162636465666768", "45080568656c6c6f"}) {
            assertThrows(IOException.class, () -> YooseeQuickLz.decompress(HexFormat.of().parseHex(data), 5));
        }
    }

    @Test void decodesLevelTwoDictionaryMatchWithOverlappingCopies() throws Exception {
        var source = new byte[24];
        source[0] = 0x59; source[1] = 24; source[2] = 32;
        var buffer = YooseeWire.buffer(source);
        buffer.putInt(3, 0x80000008);
        source[7] = 'a'; source[8] = 'b'; source[9] = 'c';
        int hash = (0x636261 ^ (0x636261 >>> 9) ^ (0x636261 >>> 13)) & 2047;
        buffer.putShort(10, (short) (hash << 5));
        source[12] = 18;
        System.arraycopy("01234567890".getBytes(StandardCharsets.US_ASCII), 0, source, 13, 11);
        assertEquals("abc".repeat(7) + "01234567890", new String(YooseeQuickLz.decompress(source, 32), StandardCharsets.US_ASCII));
    }
}
