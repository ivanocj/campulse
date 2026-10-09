package com.ivanocj.cameras.availability.infrastructure.probe;

import com.ivanocj.cameras.availability.domain.model.TransportProtocol;
import java.io.IOException;
import java.net.InetSocketAddress;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.ivanocj.cameras.availability.infrastructure.protocol.YooseeWire.*;

class YooseeListDiscoveryTest {
    @ParameterizedTest @EnumSource(value = TransportProtocol.class, names = {"GWELL", "IOTVIDEO"})
    void validatesRecordBoundsNetworkByteOrderAndLocalReferralTrust(TransportProtocol protocol) throws Exception {
        boolean iot = protocol == TransportProtocol.IOTVIDEO;
        var frame = frame(0x7f, iot ? 0x16 : 0x2c, iot ? 68 : 48, 1, 2, iot ? 1 << 16 : 1);
        frame[30] = 1;
        int base = iot ? 32 : 36, portAt = base + (iot ? 26 : 6);
        frame[base] = 127; frame[base + 3] = 1;
        frame[portAt] = 0x12; frame[portAt + 1] = 0x34;
        var local = new InetSocketAddress("127.0.0.1", 51701);
        var publicSource = new InetSocketAddress("198.51.100.1", 51701);
        assertEquals(new InetSocketAddress("127.0.0.1", 0x1234), YooseeListDiscovery.endpoints(frame, local, protocol).getFirst());
        assertTrue(YooseeListDiscovery.endpoints(frame, publicSource, protocol).isEmpty());
        frame[30] = 2;
        assertThrows(IOException.class, () -> YooseeListDiscovery.endpoints(frame, local, protocol));
        frame[30] = 1;
        buffer(frame).putShort(iot ? 26 : 22, (short) 1);
        assertThrows(IOException.class, () -> YooseeListDiscovery.endpoints(frame, local, protocol));
    }
}
