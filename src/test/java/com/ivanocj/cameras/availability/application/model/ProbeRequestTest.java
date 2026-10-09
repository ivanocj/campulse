package com.ivanocj.cameras.availability.application.model;

import com.ivanocj.cameras.availability.domain.model.DeviceId;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProbeRequestTest {
    @Test
    void existingRequestsKeepTheSyPpcsTransportByDefault() {
        assertEquals(TransportProtocol.PPCS, new ProbeRequest(new DeviceId("FFFF-158409-EBDAC"),
                List.of(), Duration.ofSeconds(10), true).protocol());
    }

    @Test
    void invalidXqIdsAreRejectedBeforeStartingNetworkWork() {
        assertThrows(IllegalArgumentException.class, () -> new ProbeRequest(
                new DeviceId("ABCDEFG-12345678-ABCDEF"), List.of(), Duration.ofSeconds(10), true,
                TransportProtocol.XQ));
    }
}
