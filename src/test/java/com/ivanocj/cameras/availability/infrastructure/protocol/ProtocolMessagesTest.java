package com.ivanocj.cameras.availability.infrastructure.protocol;

import com.ivanocj.cameras.availability.domain.model.DeviceId;
import com.ivanocj.cameras.availability.domain.model.TransportProtocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.HexFormat;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

class ProtocolMessagesTest {
    @Test
    void xqUsesTheFullAsciiDidRatherThanTheSyBinaryIdentity() {
        assertArrayEquals(HexFormat.of().parseHex("464646462d3135383430392d4542444143000000"),
                new ProtocolMessages(TransportProtocol.XQ).identity(new DeviceId("FFFF-158409-EBDAC")));
    }

    @Test
    void syRetainsItsOriginalBinaryIdentity() {
        assertArrayEquals(HexFormat.of().parseHex("464646460000000000026ac94542444143000000"),
                new ProtocolMessages(TransportProtocol.PPCS).identity(new DeviceId("FFFF-158409-EBDAC")));
    }

    @Test
    void encodesTheXqConnectionRequestFromTheNativePacketBuilder() {
        assertArrayEquals(HexFormat.of().parseHex("f1200024"
                        + "464646462d3135383430392d4542444143000000"
                        + "00026c7d0201a8c00000000000000000"),
                new ProtocolMessages(TransportProtocol.XQ).connectRequest(new DeviceId("FFFF-158409-EBDAC"),
                        new InetSocketAddress("192.168.1.2", 32108)).encode());
    }

    @Test
    void encodesTheXqRelayRequestWithoutChangingItsCookieBytes() {
        assertArrayEquals(HexFormat.of().parseHex("f1800028"
                        + "464646462d3135383430392d4542444143000000"
                        + "00026c7d0201a8c00000000000000000" + "11223344"),
                new ProtocolMessages(TransportProtocol.XQ).relayRequest(new DeviceId("FFFF-158409-EBDAC"),
                        new InetSocketAddress("192.168.1.2", 32108), 0x11223344).encode());
    }

    @Test
    void encodesAnXqRelayPunchWithTheClientRoleAndAsciiDid() {
        assertArrayEquals(HexFormat.of().parseHex("f183001c11223344"
                        + "464646462d3135383430392d4542444143000000" + "00000000"),
                new ProtocolMessages(TransportProtocol.XQ).relayPunch(new DeviceId("FFFF-158409-EBDAC"), 0x11223344).encode());
    }
}
