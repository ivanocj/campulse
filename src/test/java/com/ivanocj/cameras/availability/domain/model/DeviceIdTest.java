package com.ivanocj.cameras.availability.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DeviceIdTest {
    @Test
    void normalizesUserInput() {
        assertEquals("FFFF-158409-EBDAC", new DeviceId("  ffff-158409-ebdac  ").value());
    }

    @Test
    void exposesTheCompleteNormalizedPrefixForDeploymentRouting() {
        assertEquals("FDTAA", new DeviceId(" fdtaa-158409-ebdac ").prefix());
    }

    @Test
    void rejectsMissingDidSegments() {
        assertThrows(IllegalArgumentException.class, () -> new DeviceId("FFFF"));
    }

    @Test
    void rejectsSegmentsThatWouldBeTruncatedByPpcs() {
        assertThrows(IllegalArgumentException.class, () -> new DeviceId("ABCDEFGH-1-A"));
    }

    @Test
    void rejectsSerialsOutsideUnsigned32BitRange() {
        assertThrows(IllegalArgumentException.class, () -> new DeviceId("F-4294967296-A"));
    }

    @Test
    void respectsThePublicHeaderDidBufferSize() {
        assertThrows(IllegalArgumentException.class, () -> new DeviceId("AAAAAAA-4294967295-AAAAAAA"));
    }
}
