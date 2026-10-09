package com.ivanocj.cameras.availability.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HichipUidTest {
    @Test
    void reproducesTheUidPythonReference() {
        assertEquals("FFFF-158409-EBDAC", HichipUid.generate("FFFF", 158409));
    }

    @Test
    void theFixedSineTableMatchesItsStrictMathDefinition() {
        for (int i = 0; i < 64; i++) {
            assertEquals((int) (long) (StrictMath.abs(StrictMath.sin(i + 1)) * 0x1_0000_0000L), HichipUid.SINE_TABLE[i],
                    "entry " + i);
        }
    }
}
