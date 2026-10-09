package com.ivanocj.cameras.availability.infrastructure.protocol;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class YooseeRc5Test {
    private static byte[] hex(String value) { return HexFormat.of().parseHex(value); }

    @Test
    void matchesPublishedRc5Word32TwelveRoundVectors() {
        try (var cipher = new YooseeRc5(8, 12, new byte[16])) {
            var block = new byte[8];
            cipher.encrypt(block, 0);
            assertArrayEquals(hex("21a5dbee154b8f6d"), block);
            cipher.decrypt(block, 0);
            assertArrayEquals(new byte[8], block);
        }
        try (var cipher = new YooseeRc5(8, 12, hex("915f4619be41b2516355a50110a9ce91"))) {
            var block = hex("21a5dbee154b8f6d");
            cipher.encrypt(block, 0);
            assertArrayEquals(hex("f7c013ac5b2b8952"), block);
        }
    }

    @Test
    void supportsBothNativeBlockWidthsAndDoesNotChangeAdjacentBytes() {
        for (int blockSize : new int[]{8, 16}) {
            try (var cipher = new YooseeRc5(blockSize, 6, hex("00112233445566778899aabbccddeeff"))) {
                var input = hex("123456789abcdef00123456789abcdef00123456789abcdeff");
                var block = input.clone();
                cipher.encrypt(block, 4);
                assertFalse(java.util.Arrays.equals(input, block));
                assertArrayEquals(java.util.Arrays.copyOf(input, 4), java.util.Arrays.copyOf(block, 4));
                cipher.decrypt(block, 4);
                assertArrayEquals(input, block);
            }
        }
    }

    @Test
    void rejectsInvalidParametersAndUseAfterKeyErasure() {
        assertThrows(IllegalArgumentException.class, () -> new YooseeRc5(4, 6, new byte[8]));
        assertThrows(IllegalArgumentException.class, () -> new YooseeRc5(8, 0, new byte[8]));
        assertThrows(IllegalArgumentException.class, () -> new YooseeRc5(8, 6, new byte[0]));
        var cipher = new YooseeRc5(8, 6, new byte[12]);
        assertThrows(IllegalArgumentException.class, () -> cipher.encrypt(new byte[7], 0));
        cipher.close();
        assertThrows(IllegalStateException.class, () -> cipher.encrypt(new byte[8], 0));
    }
}
