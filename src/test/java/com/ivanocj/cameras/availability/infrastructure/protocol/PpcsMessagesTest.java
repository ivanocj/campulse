package com.ivanocj.cameras.availability.infrastructure.protocol;

import com.ivanocj.cameras.availability.domain.model.DeviceId;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetSocketAddress;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class PpcsMessagesTest {
    @Test
    void encodesDidAndTheLegacySockaddrByteOrderExactly() {
        byte[] expected = HexFormat.of().parseHex("f1200024"
                + "464646460000000000026ac94542444143000000"
                + "00026c7d0201a8c00000000000000000");
        assertArrayEquals(expected, new ProtocolMessages(TransportProtocol.PPCS).connectRequest(new DeviceId("FFFF-158409-EBDAC"),
                new InetSocketAddress("192.168.1.2", 32108)).encode());
    }

    @Test
    void decodesTheServerAdvertisedPeerAddress() {
        assertEquals(new InetSocketAddress("192.168.1.2", 32108), PpcsMessages.readAddress(
                HexFormat.of().parseHex("00026c7d0201a8c00000000000000000"), 0));
    }

    @Test
    void rejectsAnInvalidLegacySockaddr() {
        assertThrows(IllegalArgumentException.class, () -> PpcsMessages.readAddress(new byte[16], 0));
    }
}
